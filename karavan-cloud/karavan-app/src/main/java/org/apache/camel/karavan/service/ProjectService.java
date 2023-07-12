/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.karavan.service;

import io.quarkus.scheduler.Scheduled;
import io.quarkus.vertx.ConsumeEvent;
import io.smallrye.mutiny.tuples.Tuple2;
import org.apache.camel.karavan.datagrid.DatagridService;
import org.apache.camel.karavan.datagrid.model.GitRepo;
import org.apache.camel.karavan.datagrid.model.Project;
import org.apache.camel.karavan.datagrid.model.ProjectFile;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;
import org.jboss.logging.Logger;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.Default;
import javax.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@Default
@Readiness
@ApplicationScoped
public class ProjectService implements HealthCheck{

    private static final Logger LOGGER = Logger.getLogger(ProjectService.class.getName());
    public static final String IMPORT_PROJECTS = "import-projects";

    @Inject
    DatagridService datagridService;

    @Inject
    KubernetesService kubernetesService;

    @Inject
    GitService gitService;

    @Inject
    CodeService codeService;

    @ConfigProperty(name = "karavan.default-runtime")
    String runtime;

    private AtomicBoolean readyToPull = new AtomicBoolean(false);

    @Override
    public HealthCheckResponse call() {
        if(readyToPull.get()) {
            return HealthCheckResponse.up("Git authentication is successfull.");
        }
        else {
            return HealthCheckResponse.down("Git authentication is unsuccessfull. Check your git credentials.");
        }
    }

    @Scheduled(every = "{karavan.git-pull-interval}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void pullCommits() {
        if (readyToPull.get()) {
            LOGGER.info("Pull commits...");
            Tuple2<String, Integer> lastCommit = datagridService.getLastCommit();
            gitService.getCommitsAfterCommit(lastCommit.getItem2()).forEach(commitInfo -> {
                if (!datagridService.hasCommit(commitInfo.getCommitId())) {
                    commitInfo.getRepos().forEach(repo -> {
                        Project project = importProjectFromRepo(repo);
                        kubernetesService.createPipelineRun(project);
                    });
                    datagridService.saveCommit(commitInfo.getCommitId(), commitInfo.getTime());
                }
                datagridService.saveLastCommit(commitInfo.getCommitId());
            });
        }
    }

    void importCommits() {
        LOGGER.info("Import commits...");
        gitService.getAllCommits().forEach(commitInfo -> {
            datagridService.saveCommit(commitInfo.getCommitId(), commitInfo.getTime());
            datagridService.saveLastCommit(commitInfo.getCommitId());
        });
        readyToPull.set(true);
    }

    @ConsumeEvent(value = IMPORT_PROJECTS, blocking = true)
    void importProjects(String data) {
        if (datagridService.getProjects().isEmpty()) {
            importAllProjects();
        }
        addTemplatesProject();
        importCommits();
    }
    private void importAllProjects() {
        LOGGER.info("Import projects from Git");
        try {
            List<GitRepo> repos = gitService.readProjectsToImport();
            repos.forEach(repo -> {
                Project project;
                String folderName = repo.getName();
                if (folderName.equals(Project.NAME_TEMPLATES)) {
                    project = new Project(Project.NAME_TEMPLATES, "Templates", "Templates", "", repo.getCommitId(), repo.getLastCommitTimestamp());
                } else if (folderName.equals(Project.NAME_KAMELETS)){
                    project = new Project(Project.NAME_KAMELETS, "Custom Kamelets", "Custom Kamelets", "", repo.getCommitId(), repo.getLastCommitTimestamp());
//                } else if (folderName.equals(Project.NAME_PIPELINES)){
//                    project = new Project(Project.NAME_PIPELINES, "Pipelines", "CI/CD Pipelines", "", repo.getCommitId(), repo.getLastCommitTimestamp());
                } else {
                    project = getProjectFromRepo(repo);
                }
                datagridService.saveProject(project);

                repo.getFiles().forEach(repoFile -> {
                    ProjectFile file = new ProjectFile(repoFile.getName(), repoFile.getBody(), folderName, repoFile.getLastCommitTimestamp());
                    datagridService.saveProjectFile(file);
                });
            });
            addKameletsProject();
        } catch (Exception e) {
            LOGGER.error("Error during project import", e);
        }
    }

    public Project importProject(String projectId) {
        LOGGER.info("Import project from Git " + projectId);
        try {
            GitRepo repo = gitService.readProjectFromRepository(projectId);
            return importProjectFromRepo(repo);
        } catch (Exception e) {
            LOGGER.error("Error during project import", e);
            return null;
        }
    }

    private Project importProjectFromRepo(GitRepo repo) {
        LOGGER.info("Import project from GitRepo " + repo.getName());
        try {
            Project project = getProjectFromRepo(repo);
            datagridService.saveProject(project);
            repo.getFiles().forEach(repoFile -> {
                ProjectFile file = new ProjectFile(repoFile.getName(), repoFile.getBody(), repo.getName(), repoFile.getLastCommitTimestamp());
                datagridService.saveProjectFile(file);
            });
            return project;
        } catch (Exception e) {
            LOGGER.error("Error during project import", e);
            return null;
        }
    }

    public Project getProjectFromRepo(GitRepo repo) {
        String folderName = repo.getName();
        String propertiesFile = codeService.getPropertiesFile(repo);
        String projectName = codeService.getProjectName(propertiesFile);
        String projectDescription = codeService.getProjectDescription(propertiesFile);
        String runtime = codeService.getProjectRuntime(propertiesFile);
        return new Project(folderName, projectName, projectDescription, runtime, repo.getCommitId(), repo.getLastCommitTimestamp());
    }

    public Project commitAndPushProject(String projectId, String message) throws Exception {
        Project p = datagridService.getProject(projectId);
        List<ProjectFile> files = datagridService.getProjectFiles(projectId);
        RevCommit commit = gitService.commitAndPushProject(p, files, message);
        String commitId = commit.getId().getName();
        Long lastUpdate = commit.getCommitTime() * 1000L;
        p.setLastCommit(commitId);
        p.setLastCommitTimestamp(lastUpdate);
        datagridService.saveProject(p);
        datagridService.saveCommit(commitId, commit.getCommitTime());
        return p;
    }

    void addKameletsProject() {
        LOGGER.info("Add custom kamelets project if not exists");
        try {
            Project kamelets  = datagridService.getProject(Project.NAME_KAMELETS);
            if (kamelets == null) {
                kamelets = new Project(Project.NAME_KAMELETS, "Custom Kamelets", "Custom Kamelets", "", "", Instant.now().toEpochMilli());
                datagridService.saveProject(kamelets);
                commitAndPushProject(Project.NAME_KAMELETS, "Add custom kamelets");
            }
        } catch (Exception e) {
            LOGGER.error("Error during custom kamelets project creation", e);
        }
    }

    void addTemplatesProject() {
        LOGGER.info("Add templates project if not exists");
        try {
            Project templates  = datagridService.getProject(Project.NAME_TEMPLATES);
            if (templates == null) {
                templates = new Project(Project.NAME_TEMPLATES, "Templates", "Templates", "", "", Instant.now().toEpochMilli());
                datagridService.saveProject(templates);

                codeService.getApplicationPropertiesTemplates().forEach((name, value) -> {
                    ProjectFile file = new ProjectFile(name, value, Project.NAME_TEMPLATES, Instant.now().toEpochMilli());
                    datagridService.saveProjectFile(file);
                });
                commitAndPushProject(Project.NAME_TEMPLATES, "Add default templates");
            }
        } catch (Exception e) {
            LOGGER.error("Error during templates project creation", e);
        }
    }

    void addPipelinesProject() {
        LOGGER.info("Add pipelines project if not exists");
        try {
            Project pipelines  = datagridService.getProject(Project.NAME_PIPELINES);
            if (pipelines == null) {
                pipelines = new Project(Project.NAME_PIPELINES, "Pipelines", "CI/CD Pipelines", "", "", Instant.now().toEpochMilli());
                datagridService.saveProject(pipelines);

                codeService.getApplicationPropertiesTemplates().forEach((name, value) -> {
                    ProjectFile file = new ProjectFile(name, value, Project.NAME_PIPELINES, Instant.now().toEpochMilli());
                    datagridService.saveProjectFile(file);
                });
                commitAndPushProject(Project.NAME_PIPELINES, "Add default pipelines");
            }
        } catch (Exception e) {
            LOGGER.error("Error during pipelines project creation", e);
        }
    }
}
