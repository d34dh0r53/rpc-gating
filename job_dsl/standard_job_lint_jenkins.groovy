library "rpc-gating-master"
common.globalWraps(){
  common.standard_job_slave(env.SLAVE_TYPE) {
    try {
      String repoDir = "${WORKSPACE}/${env.RE_JOB_REPO_NAME}"
      String repoURL
      String repoBranch
      stage("Checkout"){
        if ( env.ghprbPullId != null ) {
          repoURL = "https://github.com/${env.ghprbGhRepository}.git"
          repoBranch = "origin/pr/${env.ghprbPullId}/merge"
          print("Triggered by PR: ${env.ghprbPullLink}")
        } else {
          repoURL = env.REPO_URL
          repoBranch = env.BRANCH
        }
        print("Repo: ${repoURL} Branch: ${repoBranch}")
        common.clone_with_pr_refs(repoDir, repoURL, repoBranch)
      }
      def jobSources = readYaml text: JOB_SOURCES
      // Remove the repo being tested, because it will be added as a local source, so doesn't need to be cloned
      // by path setup
      // Remove rpc-gating because it's already been cloned.
      String git_repo_url = common.https_to_ssh_github_url(env.REPO_URL)
      jobSources.removeAll { it.repo == git_repo_url || it.repo == "git@github.com:rcbops/rpc-gating"}
      String sourcesArgs = jobSources.collect {"--job-source \"${it.repo};${it.commitish}\""}.join(' ')
      stage("Lint Jenkins"){
        withCredentials([
          string(
            credentialsId: 'rpc-jenkins-svc-github-pat',
            variable: 'PAT'
          )
        ]){
          withEnv(
            [
              "CHECK_JENKINS_ONLY='true'",
            ]
          ){
            sshagent (credentials:['rpc-jenkins-svc-github-ssh-key']){
              sh """#!/bin/bash -xe
                # venv needed for click lib used by jjb-path-setup
                source .venv/bin/activate
                  export JJB_PATHS_OVERRIDE=\$(rpc-gating/scripts/jjb-path-setup.py \
                    ${sourcesArgs} \
                    --job-source '${repoDir}'\
                    --job-source '${WORKSPACE}/rpc-gating')
                deactivate
                pushd rpc-gating
                  ./lint.sh
                popd
              """
            }
          }
        }
      }
    } catch(e) {
      print(e)
      // Only create failure card when run as a post-merge job
      if (env.ghprbPullId == null && ! common.isUserAbortedBuild() && env.JIRA_PROJECT_KEY != '') {
        print("Creating build failure issue.")
        common.build_failure_issue(env.JIRA_PROJECT_KEY)
      } else {
        print("Skipping build failure issue creation.")
      }
      throw e
    }
  }
}
