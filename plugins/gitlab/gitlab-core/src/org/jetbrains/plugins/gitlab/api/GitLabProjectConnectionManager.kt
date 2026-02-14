// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.plugins.gitlab.api

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import git4idea.remote.hosting.SingleHostedGitRepositoryConnectionManager
import git4idea.remote.hosting.SingleHostedGitRepositoryConnectionManagerImpl
import git4idea.remote.hosting.ValidatingHostedGitRepositoryConnectionFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.jetbrains.plugins.gitlab.GitLabProjectsManager
import org.jetbrains.plugins.gitlab.api.request.findProject
import org.jetbrains.plugins.gitlab.api.request.getCurrentUser
import org.jetbrains.plugins.gitlab.api.request.getProject
import org.jetbrains.plugins.gitlab.authentication.accounts.GitLabAccount
import org.jetbrains.plugins.gitlab.authentication.accounts.GitLabAccountManager
import org.jetbrains.plugins.gitlab.data.GitLabProjectDetails
import org.jetbrains.plugins.gitlab.util.GitLabProjectMapping
import org.jetbrains.plugins.gitlab.util.GitLabProjectPath

private val LOG = logger<GitLabProjectConnectionManager>()

@Service(Service.Level.PROJECT)
internal class GitLabProjectConnectionManager(private val project: Project, cs: CoroutineScope) :
  SingleHostedGitRepositoryConnectionManager<GitLabProjectMapping, GitLabAccount, GitLabProjectConnection> {

  private val accountManager = service<GitLabAccountManager>()
  private val projectsManager = project.service<GitLabProjectsManager>()

  private val connectionFactory = ValidatingHostedGitRepositoryConnectionFactory(
    { projectsManager },
    { accountManager }
  ) { glProjectMapping, account, tokenState ->
    doOpenConnectionIn(this, glProjectMapping, account, tokenState)
  }

  private val delegate = SingleHostedGitRepositoryConnectionManagerImpl(cs, connectionFactory)

  override val connectionState: StateFlow<GitLabProjectConnection?>
    get() = delegate.connectionState

  init {
    cs.launch {
      accountManager.accountsState.collect {
        val currentAccount = connectionState.value?.account
        if (currentAccount != null && !it.contains(currentAccount)) {
          closeConnection()
        }
      }
    }
  }

  private suspend fun doOpenConnectionIn(
    scope: CoroutineScope,
    glProjectMapping: GitLabProjectMapping,
    account: GitLabAccount,
    tokenState: StateFlow<String>,
  ): GitLabProjectConnection {
    val apiClient = service<GitLabApiManager>().getClient(account.server) { tokenState.value }
    val glMetadata = apiClient.getMetadataOrNull()
    val currentUser = apiClient.graphQL.getCurrentUser()
    val projectDetails = apiClient.loadProjectDetails(glProjectMapping.repository.projectPath)
    return GitLabProjectConnection(project,
                                   scope,
                                   glProjectMapping,
                                   projectDetails,
                                   account,
                                   currentUser,
                                   apiClient,
                                   glMetadata,
                                   tokenState)
  }

  private suspend fun GitLabApi.loadProjectDetails(
    projectPath: GitLabProjectPath,
  ): GitLabProjectDetails {
    val projectData = graphQL.findProject(projectPath).body()
    if (projectData != null) {
      return GitLabProjectDetails(projectPath, projectData)
    }
    else {
      LOG.warn("Project $projectPath not found on server $server. Trying to fetch with REST API")
      val restProjectResponse = rest.getProject(projectPath).body()
      val actualProjectPath = GitLabProjectPath.extractProjectPath(restProjectResponse.pathWithNamespace)
                              ?: error("Unable to parse the project path: ${restProjectResponse.pathWithNamespace}")
      val projectData = graphQL.findProject(actualProjectPath).body()
                        ?: error("Could not find the project $actualProjectPath. Check if the project exists and you have access to it.")
      return GitLabProjectDetails(actualProjectPath, projectData)
    }
  }

  override suspend fun openConnection(repo: GitLabProjectMapping, account: GitLabAccount): GitLabProjectConnection? =
    delegate.openConnection(repo, account)

  override suspend fun closeConnection() = delegate.closeConnection()
}