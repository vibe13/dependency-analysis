package org.jboss.pnc.mavenrepositorymanager;

import org.commonjava.aprox.client.core.Aprox;
import org.commonjava.aprox.client.core.AproxClientException;
import org.commonjava.aprox.folo.client.AproxFoloAdminClientModule;
import org.commonjava.aprox.folo.client.AproxFoloContentClientModule;
import org.commonjava.aprox.model.core.Group;
import org.commonjava.aprox.model.core.HostedRepository;
import org.commonjava.aprox.model.core.StoreKey;
import org.commonjava.aprox.model.core.StoreType;
import org.commonjava.aprox.promote.client.AproxPromoteClientModule;
import org.jboss.logging.Logger;
import org.jboss.pnc.common.Configuration;
import org.jboss.pnc.common.json.ConfigurationParseException;
import org.jboss.pnc.common.json.moduleconfig.MavenRepoDriverModuleConfig;
import org.jboss.pnc.model.Artifact;
import org.jboss.pnc.model.BuildConfiguration;
import org.jboss.pnc.model.BuildRecord;
import org.jboss.pnc.model.BuildRecordSet;
import org.jboss.pnc.model.ProductVersion;
import org.jboss.pnc.model.RepositoryType;
import org.jboss.pnc.spi.repositorymanager.RepositoryManager;
import org.jboss.pnc.spi.repositorymanager.RepositoryManagerException;
import org.jboss.pnc.spi.repositorymanager.model.RepositorySession;
import org.jboss.pnc.spi.repositorymanager.model.RunningRepositoryPromotion;

import java.util.List;

import javax.annotation.PreDestroy;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

/**
 * Implementation of {@link RepositoryManager} that manages an <a href="https://github.com/jdcasey/aprox">AProx</a> instance to
 * support repositories for Maven-ish builds.
 *
 * Created by <a href="mailto:matejonnet@gmail.com">Matej Lazar</a> on 2014-11-25.
 * 
 * @author <a href="mailto:jdcasey@commonjava.org">John Casey</a>
 */
@ApplicationScoped
public class RepositoryManagerDriver implements RepositoryManager {
    
    private static final Logger log = Logger.getLogger(RepositoryManagerDriver.class);
    
    public static final String DRIVER_ID = "maven-repo-driver";

    private static final String GROUP_ID_FORMAT = "product+%s+%s";

    private static final String REPO_ID_FORMAT = "build+%s+%s";

    public static final String PUBLIC_GROUP_ID = "public";

    public static final String SHARED_RELEASES_ID = "shared-releases";

    public static final String SHARED_IMPORTS_ID = "shared-imports";

    private Aprox aprox;

    @Deprecated
    public RepositoryManagerDriver() { // workaround for CDI constructor parameter injection bug
    }

    @SuppressWarnings("resource")
    @Inject
    public RepositoryManagerDriver(Configuration<MavenRepoDriverModuleConfig> configuration) {
        MavenRepoDriverModuleConfig config;
        try {
            config = configuration.getModuleConfig(MavenRepoDriverModuleConfig.class);
            String baseUrl = config.getBaseUrl();
            if (baseUrl.endsWith("/")) {
                baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
            }

            if (!baseUrl.endsWith("/api")) {
                baseUrl += "/api";
            }

            aprox = new Aprox(baseUrl, new AproxFoloAdminClientModule(), new AproxFoloContentClientModule(),
                    new AproxPromoteClientModule()).connect();

            setupGlobalRepos();

        } catch (ConfigurationParseException e) {
            throw new IllegalStateException("Cannot read configuration for " + RepositoryManagerDriver.DRIVER_ID + ".", e);
        } catch (AproxClientException e) {
            throw new IllegalStateException("Failed to setup shared-releases or shared-imports hosted repository: "
                    + e.getMessage(), e);
        }
    }

    @PreDestroy
    public void shutdown() {
        aprox.close();
    }

    /**
     * Only supports {@link RepositoryType#MAVEN}.
     */
    @Override
    public boolean canManage(RepositoryType managerType) {
        return (managerType == RepositoryType.MAVEN);
    }

    /**
     * Use the AProx client API to setup global and build-set level repos and groups, then setup the repo/group needed for this
     * build. Calculate the URL to use for resolving artifacts using the AProx Folo API (Folo is an artifact activity-tracker).
     * Return a new session ({@link MavenRepositorySession}) containing this information.
     * 
     * @throws RepositoryManagerException In the event one or more repositories or groups can't be created to support the build
     *         (or product, or shared-releases).
     */
    @Override
    public RepositorySession createBuildRepository(BuildConfiguration buildConfiguration, BuildRecordSet buildRecordSet)
            throws RepositoryManagerException {

        String productRepoId = getRecordSetRepoId(buildRecordSet);
        if (productRepoId != null) {
            try {
                setupProductRepos(productRepoId, buildRecordSet);
            } catch (AproxClientException e) {
                throw new RepositoryManagerException("Failed to setup product-local hosted repository or repository group: %s",
                        e, e.getMessage());
            }
        }

        // TODO Better way to generate id that doesn't rely on System.currentTimeMillis() but will still be relatively fast.
        String buildRepoId = String.format(REPO_ID_FORMAT, safeUrlPart(buildConfiguration.getProject().getName()),
                System.currentTimeMillis());
        try {
            setupBuildRepos(buildRepoId, productRepoId, buildConfiguration, buildRecordSet);
        } catch (AproxClientException e) {
            throw new RepositoryManagerException("Failed to setup build-local hosted repository or repository group: %s", e,
                    e.getMessage());
        }

        // since we're setting up a group/hosted repo per build, we can pin the tracking ID to the build repo ID.
        String url;

        try {
            url = aprox.module(AproxFoloContentClientModule.class).trackingUrl(buildRepoId, StoreType.group, buildRepoId);
        } catch (AproxClientException e) {
            throw new RepositoryManagerException("Failed to retrieve AProx client module for the artifact tracker: %s", e,
                    e.getMessage());
        }

        return new MavenRepositorySession(aprox, buildRepoId, productRepoId, new MavenRepositoryConnectionInfo(url));
    }


    private String getRecordSetRepoId(BuildRecordSet buildRecordSet) {
        // FIXME: This seems wrong. What if there's no product? However, there's no way to tell which BuildConfigurationSet
        // context we're working within.
        ProductVersion pv = buildRecordSet.getProductVersion();
        if (pv == null) {
            return null;
        }

        return String.format(GROUP_ID_FORMAT, safeUrlPart(pv.getProduct().getName()), safeUrlPart(pv.getVersion()));
    }

    /**
     * Create the hosted repository and group necessary to support a single build. The hosted repository holds artifacts
     * uploaded from the build, and the group coordinates access to this hosted repository, along with content from the
     * product-level content group with which this build is associated. The group also provides a tracking target, so the
     * repository manager can keep track of downloads and uploads for the build.
     * 
     * @param buildRepoId
     * @param productRepoId
     * @throws AproxClientException
     */
    private void setupBuildRepos(String buildRepoId, String productRepoId, BuildConfiguration buildConfig,
            BuildRecordSet buildRecords) throws AproxClientException {
        // if the build-level group doesn't exist, create it.
        if (!aprox.stores().exists(StoreType.group, buildRepoId)) {
            // if the product-level storage repo (for in-progress product builds) doesn't exist, create it.
            if (!aprox.stores().exists(StoreType.hosted, buildRepoId)) {
                HostedRepository buildArtifacts = new HostedRepository(buildRepoId);
                buildArtifacts.setAllowSnapshots(true);
                buildArtifacts.setAllowReleases(true);

                aprox.stores().create(buildArtifacts,
                        "Creating hosted repository for build: " + buildRepoId + " of: " + buildConfig.getProject().getName(),
                        HostedRepository.class);
            }

            Group buildGroup = new Group(buildRepoId);

            // Priorities for build-local group:

            // 1. build-local artifacts
            buildGroup.addConstituent(new StoreKey(StoreType.hosted, buildRepoId));

            if (productRepoId != null) {
                // 2. product-level group
                buildGroup.addConstituent(new StoreKey(StoreType.group, productRepoId));
            }
            // 2. Global-level repos, for captured/shared artifacts and access to the outside world
            addGlobalConstituents(buildGroup);

            aprox.stores().create(
                    buildGroup,
                    "Creating repository group for resolving artifacts in build: " + buildRepoId + " of: "
                            + buildConfig.getProject().getName(), Group.class);
        }
    }

    /**
     * Lazily create product-level hosted repository and group if they don't exist. The group uses the following content
     * preference order:
     * <ol>
     * <li>product-level hosted repository (artifacts built for this product release)</li>
     * <li>global shared-releases hosted repository (contains artifacts from "released" product versions)</li>
     * <li>global shared-imports hosted repository (contains anything imported for a previous build)</li>
     * <li>the 'public' group, which manages the allowed remote repositories from which imports can be downloaded</li>
     * </ol>
     * 
     * @param productRepoId
     * @param buildRecordSet
     * @throws AproxClientException
     */
    private void setupProductRepos(String productRepoId, BuildRecordSet buildRecordSet) throws AproxClientException {
        ProductVersion pv = buildRecordSet.getProductVersion();

        // if the product-level group doesn't exist, create it.
        if (!aprox.stores().exists(StoreType.group, productRepoId)) {
            Group productGroup = new Group(productRepoId);

            aprox.stores().create(
                    productGroup,
                    "Creating group: " + productRepoId + " for grouping repos of builds related to: "
                            + pv.getProduct().getName() + ":" + pv.getVersion(), Group.class);
        }
    }

    private void addGlobalConstituents(Group group) {
        // 1. global shared-releases artifacts
        group.addConstituent(new StoreKey(StoreType.group, SHARED_RELEASES_ID));

        // 2. global shared-imports artifacts
        group.addConstituent(new StoreKey(StoreType.hosted, SHARED_IMPORTS_ID));

        // 3. public group, containing remote proxies to the outside world
        group.addConstituent(new StoreKey(StoreType.group, PUBLIC_GROUP_ID));
    }

    /**
     * Lazily create the shared-releases and shared-imports global hosted repositories if they don't already exist.
     * 
     * @throws AproxClientException
     */
    private void setupGlobalRepos() throws AproxClientException {
        // if the global shared-releases repository doesn't exist, create it.
        if (!aprox.stores().exists(StoreType.group, SHARED_RELEASES_ID)) {
            Group sharedArtifacts = new Group(SHARED_RELEASES_ID);

            aprox.stores().create(sharedArtifacts, "Creating global shared-builds repository group.", Group.class);
        }

        // if the global imports repo doesn't exist, create it.
        if (!aprox.stores().exists(StoreType.hosted, SHARED_IMPORTS_ID)) {
            HostedRepository sharedImports = new HostedRepository(SHARED_IMPORTS_ID);
            sharedImports.setAllowSnapshots(false);
            sharedImports.setAllowReleases(true);

            aprox.stores().create(sharedImports, "Creating global repository for hosting external imports used in builds.",
                    HostedRepository.class);
        }
    }

    /**
     * Sift out spaces, pipe characters and colons (things that don't play well in URLs) from the project name, and convert them
     * to dashes. This is only for naming repositories, so an approximate match to the project in question is fine.
     */
    private String safeUrlPart(String name) {
        return name.replaceAll("\\W+", "-").replaceAll("[|:]+", "-");
    }

    /**
     * Convenience method for tests.
     */
    protected Aprox getAprox() {
        return aprox;
    }

    @Override
    public RunningRepositoryPromotion promoteRepository(BuildRecord buildRecord, BuildRecordSet buildRecordSet)
            throws RepositoryManagerException {

        // FIXME: This is AWFUL! Need to store the buildRepoId in the record.
        List<Artifact> artifacts = buildRecord.getBuiltArtifacts();
        String buildRepo = null;

        arts: for (Artifact artifact : artifacts) {
            String url = artifact.getDeployUrl();
            String[] parts = url.split("/");
            for (int i = 0; i < parts.length; i++) {
                if (StoreType.hosted.singularEndpointName().equals(parts[i])) {
                    if (parts.length <= i + 1) {
                        continue arts;
                    }

                    buildRepo = parts[i + 1];
                    break;
                }
            }
        }

        return new MavenRunningPromotion(buildRepo, getRecordSetRepoId(buildRecordSet), aprox);
    }

}
