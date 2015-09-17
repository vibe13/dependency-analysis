/**
 * JBoss, Home of Professional Open Source.
 * Copyright 2014 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jboss.pnc.model;

import org.hibernate.annotations.ForeignKey;

import javax.persistence.*;
import javax.validation.constraints.NotNull;
import java.util.Set;

/**
 * The audited record of a build configuration. Each change to the build configuration table is recorded in the audit table.
 * This class provides access to a specific version of a build configuration.
 *
 */
@Entity
@Table(name = "buildconfiguration_aud")
public class BuildConfigurationAudited implements GenericEntity<IdRev> {

    private static final long serialVersionUID = 0L;

    /**
     * The id of the build configuration this record is associated with
     */
    @Column(insertable = false, updatable = false)
    private Integer id;

    /**
     * The table revision which identifies version of the build config
     */
    @Column(insertable = false, updatable = false)
    private Integer rev;

    @EmbeddedId
    private IdRev idRev;

    @NotNull
    private String name;

    private String buildScript;

    private String scmRepoURL;

    private String scmRevision;

    private String description;

    @NotNull
    @ManyToOne
    @ForeignKey(name = "fk_buildconfiguration_aud_project")
    private Project project;

    @NotNull
    @ManyToOne
    @ForeignKey(name = "fk_buildconfiguration_aud_environment")
    private Environment environment;

    @OneToMany(fetch = FetchType.LAZY, mappedBy = "buildConfigurationAudited")
    private Set<BuildRecord> buildRecords;

    /**
     * Instantiates a new project build configuration.
     */
    public BuildConfigurationAudited() {
    }

    public IdRev getIdRev() {
        return idRev;
    }

    public void setIdRev(IdRev idRev) {
        this.idRev = idRev;
    }

    /**
     * @return the id
     */
    @Override
    public IdRev getId() {
        return idRev;
    }

    @Override
    public void setId(IdRev idRev) {
        throw new UnsupportedOperationException("Not supported in audited entity");
    }

    /**
     * @param id the id to set
     */
    public void setId(Integer id) {
        this.id = id;
    }

    /**
     * @return the revision number generated by hibernate envers.
     */
    public Integer getRev() {
        return rev;
    }

    /**
     * @param rev the revision number of this entity
     */
    public void setRev(Integer rev) {
        this.rev = rev;
    }

    /**
     * @return the name
     */
    public String getName() {
        return name;
    }

    /**
     * @param name the name to set
     */
    public void setName(String name) {
        this.name = name;
    }

    /**
     * @return the buildScript
     */
    public String getBuildScript() {
        return buildScript;
    }

    /**
     * @param buildScript the buildScript to set
     */
    public void setBuildScript(String buildScript) {
        this.buildScript = buildScript;
    }

    /**
     * @return the scmRepoURL
     */
    public String getScmRepoURL() {
        return scmRepoURL;
    }

    /**
     * @param scmRepoURL the scmRepoURL to set
     */
    public void setScmRepoURL(String scmRepoURL) {
        this.scmRepoURL = scmRepoURL;
    }

    /**
     * @return the scmRevision
     */
    public String getScmRevision() {
        return scmRevision;
    }

    /**
     * @param scmRevision the scmRevision to set
     */
    public void setScmRevision(String scmRevision) {
        this.scmRevision = scmRevision;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    /**
     * @return the project
     */
    public Project getProject() {
        return project;
    }

    /**
     * @param project the project to set
     */
    public void setProject(Project project) {
        this.project = project;
    }

    /**
     * @return the environment
     */
    public Environment getEnvironment() {
        return environment;
    }

    /**
     * @param environment the environment to set
     */
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    public Set<BuildRecord> getBuildRecords() {
        return buildRecords;
    }

    public void setBuildRecords(Set<BuildRecord> buildRecords) {
        this.buildRecords = buildRecords;
    }

    @Override
    public String toString() {
        return "BuildConfigurationAudit [project=" + project + ", name=" + name + ", id=" + id + ", rev=" + rev + "]";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;

        BuildConfigurationAudited that = (BuildConfigurationAudited) o;

        return (idRev != null ? idRev.equals(that.idRev) : false);
    }

    @Override
    public int hashCode() {
        return idRev != null ? idRev.hashCode() : 0;
    }

    public static class Builder {
        private BuildConfiguration buildConfiguration;
        private Integer id;
        private Integer rev;

        public static Builder newBuilder() {
            return new Builder();
        }

        public BuildConfigurationAudited build() {
            BuildConfigurationAudited configurationAudited = new BuildConfigurationAudited();
            configurationAudited.setId(buildConfiguration.getId());
            configurationAudited.setBuildRecords(buildConfiguration.getBuildRecords());
            configurationAudited.setBuildScript(buildConfiguration.getBuildScript());
            configurationAudited.setDescription(buildConfiguration.getDescription());
            configurationAudited.setEnvironment(buildConfiguration.getEnvironment());
            configurationAudited.setName(buildConfiguration.getName());
            configurationAudited.setDescription(buildConfiguration.getDescription());
            configurationAudited.setScmRepoURL(buildConfiguration.getScmRepoURL());
            configurationAudited.setScmRevision(buildConfiguration.getScmRevision());
            configurationAudited.setRev(rev);
            configurationAudited.setIdRev(new IdRev(id, rev));
            return configurationAudited;
        }

        public Builder buildConfiguration(BuildConfiguration buildConfiguration) {
            this.buildConfiguration = buildConfiguration;
            return this;
        }

        public Builder rev(Integer rev) {
            this.rev = rev;
            return this;
        }

    }
}
