package com.cloudogu.ces.cesbuildlib

class Git implements Serializable {
    private script
    Sh sh
    def credentials = null

    Git(script, credentials) {
        this(script)
        this.credentials = credentials
    }

    Git(script) {
        this.script = script
        this.sh = new Sh(script)
    }

    def call(args) {
        git(args)
    }

    /**
     * Credential-aware wrapper around the global "git" step.
     */
    private def git(args) {

        if (credentials != null) {

            // args instanceof Map ist not allowed due to sandboxing. So find it out the hard way :-(
            try {
                if (!args.containsKey('credentialsId')) {
                    args.put('credentialsId', credentials)
                }
            } catch (MissingMethodException ignored) {
                // This exception indicates that we don't have a Map. Assume url String and add credentials
                args = [url: args.toString(), credentialsId: credentials]
            }
        }
        script.git args
    }

    void clean(String excludes) {
        String excludesParam = ""
        if (excludes != null && "" != excludes) {
            excludesParam = " --exclude $excludes"
        }
        // Remove all untracked files
        script.sh "git clean -df$excludesParam"
        //Clear all unstaged changes
        script.sh 'git checkout -- .'
    }

    /**
     * This is just a convenience wrapper around {@code env.BRANCH_NAME}, provided by jenkins.
     * <b>Only works for multi-branch projects!</b>
     *
     * @return name of the current branch.
     */
    String getBranchName() {
        script.env.BRANCH_NAME
    }

    /**
     * @return the part of the branch name after the slash
     */
    String getSimpleBranchName() {
        return branchName.substring(branchName.lastIndexOf('/') + 1)
    }

    /**
     * @return if this branch is a release branch according to git flow
     */
    boolean isReleaseBranch() {
        return getBranchName().startsWith("release/");
    }

    /**
     * @return the Git Author of HEAD, in the following form <code>User Name &lt;user.name@doma.in&gt;</code>
     */
    String getCommitAuthorComplete() {
        sh.returnStdOut "git --no-pager show -s --format='%an <%ae>' HEAD"
    }

    String getCommitAuthorName() {
        return getCommitAuthorComplete().replaceAll(" <.*", "")
    }

    String getCommitAuthorEmail() {
        def matcher = getCommitAuthorComplete() =~ "<(.*?)>"
        matcher ? matcher[0][1] : ""
    }

    String getCommitMessage() {
        sh.returnStdOut "git log -1 --pretty=%B"
    }

    String getCommitHash() {
        sh.returnStdOut "git rev-parse HEAD"
    }

    String getCommitHashShort() {
        sh.returnStdOut "git rev-parse --short HEAD"
    }

    /**
     * @return the URL of the Git repository, e.g. {@code https://github.com/orga/repo.git}
     */
    String getRepositoryUrl() {
        sh.returnStdOut "git remote get-url origin"
    }

    /**
     * @return the name of the GitHub Repository e.g. {@code repository/url} or empty String, if no GitHub repo.
     */
    @Deprecated
    String getGitHubRepositoryName() {
        if (!repositoryUrl.contains('github.com')) {
            return ''
        }
        return repositoryName
    }

    /**
     * Creates a release on Github and fills it with the changes provided
     */
    void addGithubRelease(String releaseVersion, String changes, Git git){
        branchName = getSimpleBranchName()
        if (credentials) {
            echo "Creating Github release..."
            script.withCredentials([script.usernamePassword(credentialsId: credentials, usernameVariable: 'GIT_AUTH_USR', passwordVariable: 'GIT_AUTH_PSW')]) {
                body = "'{\"tag_name\": \"${releaseVersion}\", \"target_commitish\": \"master\", \"name\": \"${releaseVersion}\", \"body\":\"${changes}\"}'"
                apiUrl = "https://api.github.com/repos/cloudogu/${branchName}/releases"
                flags = "--request POST --data ${body} --header \"Content-Type: application/json\""
                script = "curl -u ${GIT_AUTH_USR}:${GIT_AUTH_PSW} ${flags} ${apiUrl}"
                output = sh (
                    script: script,
                    returnStdout: true
                ).trim()
                echo output
            }
            echo "Github release created..."
        } else {
            throw new Exception("Unable to create Github release without credentials")
        }
    }

    /**
     * @return the name of the Repository, assuming it is the URL is formatted like {@code host/getRepositoryName}
     */
    String getRepositoryName() {
        String repoUrl = repositoryUrl
                .replace('https://', '')
                .replace('.git', '')
        if (repoUrl.startsWith('git@')) {
            return repoUrl.substring(repoUrl.indexOf(':') + 1)
        } else {
            return repoUrl.substring(repoUrl.indexOf('/') + 1)
        }
    }

    String getTag() {
        // Note that "git name-rev --name-only --tags HEAD" always seems to append a caret (e.g. "1.0.0^")
        return sh.returnStdOut("git tag --points-at HEAD")
    }

    boolean isTag() {
        return !getTag().isEmpty()
    }

    /**
     * @return true if the specified tag exists
     */
    boolean tagExists(String tag){
        if (credentials) {
            script.withCredentials([script.usernamePassword(credentialsId: credentials, usernameVariable: 'GIT_AUTH_USR', passwordVariable: 'GIT_AUTH_PSW')]) {
                tagFound = sh (
                    script: "git -c credential.helper=\"!f() { echo username='\$GIT_AUTH_USR'; echo         password='\$GIT_AUTH_PSW'; }; f\" ls-remote origin refs/tags/${tag}",
                    returnStdout: true
                ).trim()
                if (tagFound.length() > 0) return true
                return false
            }
        } else {
            tagFound = sh ("git ls-remote origin refs/tags/${tag}").trim()
            if (tagFound.length() > 0) return true
            return false
        }
    }

    def add(String pathspec) {
        script.sh "git add $pathspec"
    }

    /**
     * Commits using the name and email of the last committer as author and committer.
     *
     * @param message
     */
    void commit(String message) {
        commit(message, commitAuthorName, commitAuthorEmail)
    }

    /**
     * Commits using the specific name and emails as author and committer.
     *
     * @param message
     */
    void commit(String message, String authorName, String authorEmail) {
        script.withEnv(["GIT_AUTHOR_NAME=$authorName", "GIT_AUTHOR_EMAIL=$authorEmail",
                        "GIT_COMMITTER_NAME=$authorName", "GIT_COMMITTER_EMAIL=$authorEmail"]) {
            script.sh "git commit -m \"$message\""
        }
    }

    /**
     * Fetch remote branches from origin.
     */
    void fetch() {
        // we need to configure remote,
        // because jenkins configures the remote only for the current branch
        script.sh "git config 'remote.origin.fetch' '+refs/heads/*:refs/remotes/origin/*'"
        script.sh "git fetch --all"
    }

    /**
     * Switch branch of the local repository.
     * Note: In a multibranch pipeline Jenkins will only fetch the changed branch,
     * so you have to call {@link #fetch()} before checkout.
     *
     * @param branchName name of branch to switch to
     */
    void checkout(String branchName) {
        script.sh "git checkout ${branchName}"
    }

    /**
     * Merge branch into the current checked out branch.
     *
     * Note: In a multibranch pipeline Jenkins will only fetch the changed branch,
     * so you have to call {@link #fetch()} before merge.
     *
     * @param branchName name of branch to merge with
     */
    void merge(String branchName) {
        script.sh "git merge ${branchName}"
    }

    /**
     * Resolve the merge as a fast-forward when possible. When not possible,
     * refuse to merge and fails the build.
     *
     * Note: In a multibranch pipeline Jenkins will only fetch the changed branch,
     * so you have to call {@link #fetch()} before merge.
     *
     * @param branchName name of branch to merge with
     */
    void mergeFastForwardOnly(String branchName) {
        script.sh "git merge --ff-only ${branchName}"
    }

    /**
     * Pushes local to remote repo.
     *
     * @param refSpec branch or tag name
     */
    void push(String refSpec) {
        executeGitWithCredentials "push origin ${refSpec}"
    }

    /**
     * Commits and pushes a folder to the <code>gh-pages</code> branch of the current repo.
     * Can be used to conveniently deliver websites. See https://pages.github.com/
     *
     * Uses the name and email of the last committer as author and committer.
     *
     * Note that the branch is temporarily checked out to the <code>.gh-pages</code> folder.
     *
     * @param workspaceFolder
     * @param commitMessage
     */
    void pushGitHubPagesBranch(String workspaceFolder, String commitMessage, String subFolder = '.') {
        def ghPagesTempDir = '.gh-pages'
        try {
            script.dir(ghPagesTempDir) {
                git url: repositoryUrl, branch: 'gh-pages', changelog: false, poll: false

                script.sh "mkdir -p ${subFolder}"
                script.sh "cp -rf ../${workspaceFolder}/* ${subFolder}"
                add '.'
                commit commitMessage
                push 'gh-pages'
            }
        } finally {
            script.sh "rm -rf ${ghPagesTempDir}"
        }
    }

    /**
     * This method executes the git command with a bash function as credential helper,
     * which return username and password from jenkins credentials.
     *
     * @param args git arguments
     */
    protected void executeGitWithCredentials(String args) {
        if (credentials) {
            script.withCredentials([script.usernamePassword(credentialsId: credentials,
                    passwordVariable: 'GIT_AUTH_PSW', usernameVariable: 'GIT_AUTH_USR')]) {
                script.sh "git -c credential.helper=\"!f() { echo username='\$GIT_AUTH_USR'; echo password='\$GIT_AUTH_PSW'; }; f\" ${args}"
            }
        } else {
            script.sh "git ${args}"
        }
    }
}
