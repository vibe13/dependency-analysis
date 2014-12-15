package org.jboss.pnc.core.test;

import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.arquillian.junit.InSequence;
import org.jboss.pnc.common.Configuration;
import org.jboss.pnc.common.Resources;
import org.jboss.pnc.core.BuildDriverFactory;
import org.jboss.pnc.core.RepositoryManagerFactory;
import org.jboss.pnc.core.builder.BuildConsumer;
import org.jboss.pnc.core.builder.BuildTask;
import org.jboss.pnc.core.builder.ProjectBuilder;
import org.jboss.pnc.core.builder.operationHandlers.OperationHandler;
import org.jboss.pnc.core.exception.CoreException;
import org.jboss.pnc.core.test.mock.BuildDriverMock;
import org.jboss.pnc.core.test.mock.DatastoreMock;
import org.jboss.pnc.model.BuildCollection;
import org.jboss.pnc.model.ProjectBuildConfiguration;
import org.jboss.pnc.model.ProjectBuildResult;
import org.jboss.pnc.model.TaskStatus;
import org.jboss.pnc.model.builder.EnvironmentBuilder;
import org.jboss.pnc.spi.builddriver.BuildJobDetails;
import org.jboss.pnc.spi.environment.EnvironmentDriverProvider;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.EmptyAsset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import javax.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Created by <a href="mailto:matejonnet@gmail.com">Matej Lazar</a> on 2014-11-23.
 */
@RunWith(Arquillian.class)
public class BuildProjectsTest {

    @Deployment
    public static JavaArchive createDeployment() {
        JavaArchive jar = ShrinkWrap.create(JavaArchive.class)
                .addClass(Configuration.class)
                .addClass(Resources.class)
                .addClass(BuildDriverFactory.class)
                .addClass(RepositoryManagerFactory.class)
                .addClass(EnvironmentBuilder.class)
                .addClass(EnvironmentDriverProvider.class)
                .addPackage(OperationHandler.class.getPackage())
                .addPackage(ProjectBuilder.class.getPackage())
                .addPackage(BuildDriverMock.class.getPackage())
                .addAsManifestResource(EmptyAsset.INSTANCE, "beans.xml")
                .addAsResource("META-INF/logging.properties");
        System.out.println(jar.toString(true));
        return jar;
    }

    @Inject
    ProjectBuilder projectBuilder;

    @Inject
    DatastoreMock datastore;

    @Inject
    Logger log;

    @Inject
    BuildConsumer buildConsumer;

    Thread consumer;

    @Before
    public void startConsumer() {
        //start build consumer
        consumer = new Thread(buildConsumer, "Build-consumer");
        consumer.start();
    }

    @After
    public void stopConsumer() {
        consumer.interrupt();
    }

    @Test
    @InSequence(10)
    public void buildSingleProjectTestCase() throws Exception {


        BuildCollection buildCollection = new TestBuildCollectionBuilder().build("foo", "Foo desc.", "1.0");
        TestProjectConfigurationBuilder configurationBuilder = new TestProjectConfigurationBuilder();

//TODO move this test to datastore test
//        projectBuilder.buildProjects(projectBuildConfigurations, buildCollection);
//        assertThat(datastore.getBuildResults()).hasSize(6);

        buildProject(configurationBuilder.build(1, "c1-java"), buildCollection);

    }

    @Test
    @InSequence(10)
    public void buildMultipleProjectsTestCase() throws Exception {

        BuildCollection buildCollection = new TestBuildCollectionBuilder().build("foo", "Foo desc.", "1.0");
        TestProjectConfigurationBuilder configurationBuilder = new TestProjectConfigurationBuilder();

        Function<TestBuildConfig, Runnable> createJob = (config) -> {
            Runnable task = () -> {
                try {
                    buildProject(config.configuration, config.collection);
                } catch (InterruptedException | CoreException e) {
                    throw new AssertionError("Something went wrong.", e);
                }
            };
            return task;
        };

        List<Runnable> list = new ArrayList();
        for (int i = 0; i < 100; i++) { //create 100 project configurations
            list.add(createJob.apply(new TestBuildConfig(configurationBuilder.build(i, "c" + i + "-java"), buildCollection)));
        }

        Function<Runnable, Thread> runInNewThread = (r) -> {
            Thread t = new Thread(r);
            t.start();
            return t;
        };

        Consumer<Thread> waitToComplete = (t) -> {
            try {
                t.join(30000);
            } catch (InterruptedException e) {
                throw new AssertionError("Interrupted while waiting threads to complete", e);
            }
        };

        List<Thread> threads = list.stream().map(runInNewThread).collect(Collectors.toList());

        Assert.assertTrue("There are no running builds.", projectBuilder.getRunningBuilds().size() > 0);
        BuildTask buildTask = projectBuilder.getRunningBuilds().iterator().next();
        Assert.assertTrue("Build has no status.", buildTask.getStatus() != null);

        threads.forEach(waitToComplete);
    }

    @Test
    @InSequence(20)
    public void checkDatabaseForResult() {
        List<ProjectBuildResult> buildResults = datastore.getBuildResults();
        Assert.assertTrue("Missing datastore results.", buildResults.size() > 10);

        ProjectBuildResult projectBuildResult = buildResults.get(0);
        String buildLog = projectBuildResult.getBuildLog();
        Assert.assertTrue("Invalid build log.", buildLog.contains("Finished: SUCCESS"));
    }

    private void buildProject(ProjectBuildConfiguration projectBuildConfigurationB1, BuildCollection buildCollection) throws InterruptedException, CoreException {
        List<TaskStatus> receivedStatuses = new ArrayList<TaskStatus>();

        int nStatusUpdates = 10;

        final Semaphore semaphore = new Semaphore(nStatusUpdates);

        Consumer<TaskStatus> onStatusUpdate = (newStatus) -> {
            receivedStatuses.add(newStatus);
            semaphore.release(1);
            log.finer("Received status update " + newStatus.getOperation());
            log.finer("Semaphore released, there are " + semaphore.availablePermits() + " free entries.");
        };
        Consumer<BuildJobDetails> onComplete = (e) -> {
            //TODO
        };
        semaphore.acquire(nStatusUpdates); //there should be 6 callbacks
        projectBuilder.buildProject(projectBuildConfigurationB1, onStatusUpdate, onComplete);
        semaphore.tryAcquire(nStatusUpdates, 30, TimeUnit.SECONDS); //wait for callback to release

        assertStatusUpdateReceived(receivedStatuses, TaskStatus.Operation.CREATE_REPOSITORY);
        assertStatusUpdateReceived(receivedStatuses, TaskStatus.Operation.BUILD_SCHEDULED);
        assertStatusUpdateReceived(receivedStatuses, TaskStatus.Operation.WAITING_BUILD_TO_COMPLETE);
        assertStatusUpdateReceived(receivedStatuses, TaskStatus.Operation.COLLECT_RESULTS);
        assertStatusUpdateReceived(receivedStatuses, TaskStatus.Operation.COMPLETING_BUILD);

    }

    private void assertStatusUpdateReceived(List<TaskStatus> receivedStatuses, TaskStatus.Operation operation) {
        boolean received = false;
        for (TaskStatus receivedStatus : receivedStatuses) {
            if (receivedStatus.getOperation().equals(operation)) {
                received = true;
                break;
            }
        }
        Assert.assertTrue("Did not received status update for " + operation +".", received );
    }

    class TestBuildConfig {
        private final ProjectBuildConfiguration configuration;
        private final BuildCollection collection;

        TestBuildConfig(ProjectBuildConfiguration configuration, BuildCollection collection) {
            this.configuration = configuration;
            this.collection = collection;
        }
    }

}
