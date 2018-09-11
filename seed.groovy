import groovy.json.JsonSlurper


// List of common views to be generated.
def commonViewsList = [ 'PRE-PR.BUILD', 'PR-OPENED.BUILD', 'PR-MERGED.BUILD', 'RELEASE',
                        'FAMILY-RELEASE', 'PUBLISH' , 'VERACODE.SCAN' , 'MANUAL-SONARQUBE-SCAN'  ]

//============================JSON PARSER====================================================
File jsonFile = new File("${WORKSPACE}/${DSL_FILE}")
def dslJobs = new JsonSlurper().parse(jsonFile)

releaseFamily=[:]

println "Generating jobs for:"

dslJobs.each { PROJECT, repositories ->
    println "\tPROJECT: $PROJECT"

    dslJobs."$PROJECT".each {
        if (it.family != "none") {
            println "\t\t family is ${it.family}"
            repoList = []
            if (releaseFamily.containsKey(it.family)) {
                println "\t\treleaseFamily has the key already"
                repoList = releaseFamily[it.family]
                println "\t\t\tsetting repolist to ${repoList}"
            } else {
                println "\t\tNew key for release Family"
                repoList = []
            }
            repoList.add(PROJECT + "." + it.repositoryName)
            println "\t\t\tnew repolist for ${it.family} is ${repoList}"
            releaseFamily[it.family] = repoList
        }
    }

    // List of repositories.
    def repoList = []

    repositories.each { repository ->

        // Create hash map of the individual repo settings.
        Map<String, Object> repoMap = new HashMap<String, Object>()
        repository.each { k, v ->
            repoMap.put(k, v)
        }

        // Assign the values from map to variables.
        REPOSITORY = repoMap.get('repositoryName')
        println "\t\tREPOSITORY: $REPOSITORY"
        repoList.add(REPOSITORY)

        DEVELOPMENT_BRANCH = repoMap.get('developmentBranch')
        SOURCE_BRANCH = repoMap.get('developmentBranch')
        BUILD_STEPS = repoMap.get('buildSteps')
        VERACODE_STEPS = repoMap.get('veracode')
        BUILD_PRE_PR = repoMap.get('pre-pr-build')
        ARTIFACTORY_REPOSITORY_ARRAY = []
        DISTRIBUTION_LIST = repoMap.get('emailingList')
        UNIT_HTML_REPORT = repoMap.get('unitHtmlReport')
        COVERAGE_HTML_REPORT = repoMap.get('coverageHtmlReport')
        UNIT_XML_REPORT = repoMap.get('unitXmlReport')

        // Set default values.
        BUILD_ARTIFACT = false
        BUILD_RPM = false
        BUILD_DOCKER = false
        BUILD_HELM = false
        BUILD_SONAR = false
        BUILD_VERACODE = false
        RUN_DOCKER_COMPOSE = false
        PUBLISH_ARTIFACT = false
        PUBLISH_RPM = false
        PUBLISH_DOCKER = false
        PUBLISH_HELM = false
        DEPLOY_DOCKER = false
        VERACODE_INCLUDE_PATTERN = "**/build/libs/${REPOSITORY}*" // comma-separated list of ant-style include patterns (relative to job's workspace root directory) of files to upload
        VERACODE_EXCLUDE_PATTERN = "**/build/libs/*javadoc*, **/build/libs/*sources*, **/build/libs/*.pom"  // comma-separated list of ant-style exclude patterns
        VERACODE_CREDENTIALS_ID = 'il_veracode_id_key'
        VERACODE_SCAN_TIMEOUT_MINUTES = '120'
        VERACODE_SCHEDULE = 'H H(4-6) * * 1-5'
        VERACODE_TEAM_LIST = 'SCM'
        UNIT_TEST_RESULTS_HEALTH_SCALE_FACTOR = (double) 5.0
        BUILD_TOOLS_PATH = "/opt/il/share/il/build-tools"
        BUILD_ARTIFACT_PATH = "build/libs"
        BUILD_RPMS_PATH = "dist/build/rpmbuild/RPMS"
        BUILD_HELM_PATH = "dist/build/helm"
        ENV_PROPERTIES_FILE = "build.properties"
        SONAR_SERVER_PROD = 'IL Sonarqube'

        // Identify the build steps to be added to PR-OPENED and PR-MERGED other than devkit build.
        if (BUILD_STEPS) {
            println "\t\t\tBUILD_STEPS:"
            BUILD_STEPS.each { artifact, properties ->
                if (artifact == "npm" || artifact == "maven" || artifact == "bower" || artifact == "vagrant" || artifact == "nuget") {
                    BUILD_ARTIFACT = true
                    if (properties.publish) {
                        ARTIFACTORY_REPOSITORY_ARRAY.add(artifact)
                        PUBLISH_ARTIFACT = true
                    }
                } else if (artifact == "rpm") {
                    BUILD_RPM = true
                    if (properties.publish) {
                        PUBLISH_RPM = true
                    }
                } else if (artifact == "docker") {
                    BUILD_DOCKER = true
                    if (properties.compose) {
                        RUN_DOCKER_COMPOSE = true
                    }
                    if (properties.publish) {
                        PUBLISH_DOCKER = true
                    }
                    if (properties.deploy) {
                        DEPLOY_DOCKER = true
                    }
                } else if (artifact == "helm") {
                    BUILD_HELM = true
                    if (properties.publish) {
                        PUBLISH_HELM = true
                    }
                } else if (artifact == "sonar") {
                    BUILD_SONAR = true
                }
                println "\t\t\t\t${artifact}"
            }
        } else {
            println "_________________________________"
            println "CONFIGURATION ERROR in Project: ${PROJECT}, Repository: ${repoMap.get('repositoryName')}."
            println "buildSteps is mandatory."
            println "_________________________________"
            exit 1
        }

        // Identify the overrides for veracode, if specified.
        if (VERACODE_STEPS != null) {
            BUILD_VERACODE = true
            VERACODE_STEPS.each { overrideName, value ->
                if (overrideName == "includePattern") {
                    VERACODE_INCLUDE_PATTERN = value
                }
                if (overrideName == "excludePattern") {
                    VERACODE_EXCLUDE_PATTERN = value
                }
                if (overrideName == "notifyTeams") {
                    VERACODE_TEAM_LIST = "${VERACODE_TEAM_LIST},${value}"
                }
            }
        }

        ARTIFACTORY_REPOSITORIES = ARTIFACTORY_REPOSITORY_ARRAY.join(' ')

        createJob(PROJECT)
    }

    //Nested views for each project
    nestedView("${PROJECT}") {
        repoList.each { repoName ->
            views {
                generateListView(delegate, repoName, "${PROJECT}.${repoName}[.].*")
            }
        }
    }
}

// Family View
releaseFamily.each { key, value ->
    println("Make Family release job for ${key}")
    createReleaseFamily(key)

}
// Nested common views
nestedView('COMMON_VIEWS') {
    commonViewsList.each { viewName ->
        views {
            generateListView(delegate, viewName, ".*${viewName}.*")
        }
    }
}

//==================================CREATE JOB METHOD==============================================
// Method to create jobs based on the arguments provided. Create PR-OPENED and PR-MERGED jobs, by default.
void createJob(PROJECT) {

    // ********Create a build job for pull request opened***********
    job("${PROJECT}.${REPOSITORY}.PR-OPENED.BUILD") {
        description('Builds in the devkit specified based on the bit bucket hook on the opening of Pull Request.')
        logRotator(-1, 5, -1, -1)
        basicBuildParameters(delegate, PROJECT, REPOSITORY)
        buildTriggerParameters(delegate)
        parameters {
            stringParam('DEVELOPMENT_BRANCH', "${DEVELOPMENT_BRANCH}", 'The GIT branch used for development.')
            stringParam('REPO_TYPE', 'dev', 'Use all of snapshots, releases and 3rd party repositories from Artifactory for finding the dependencies unless overriden to use only release repositories.')
        }
        label('pr-opened-builds')
        gitCheckout(delegate, PROJECT, REPOSITORY, 'SOURCE_BRANCH')
        wrappers {
            withSonarQubeEnv {
                installationName(SONAR_SERVER_PROD)
            }
            buildName('#${BUILD_NUMBER}, PR from: ${SOURCE_BRANCH}. PR to: ${DESTINATION_BRANCH}.')
        }
        checkForBuildTools(delegate)
        enableCodeCoverage(delegate, '\${SOURCE_BRANCH}')
        injectEnvironmentalVariables(delegate)
        prOpenSonarProperties(delegate)
        buildArtifacts(delegate)
        buildRpms(delegate)
        sonarScanPRO(delegate, SONAR_SERVER_PROD, '\${SOURCE_BRANCH}', '\${REPOSITORY}')
        buildDockerImages(delegate)
        buildHelmCharts(delegate)
        runDockerCompose(delegate)
        cleanDockerImages(delegate)
        publishers {
            stashNotifier()
            publishHtml(delegate, UNIT_HTML_REPORT)
            publishHtml(delegate, COVERAGE_HTML_REPORT)
            publishJunitTestResultReport(delegate, UNIT_XML_REPORT)
            postBuildEmails(delegate, "${DISTRIBUTION_LIST}")
            if (PUBLISH_RPM) {
                archiveArtifacts {
                    pattern("${BUILD_RPMS_PATH}/*/*.rpm")
                    onlyIfSuccessful()
                }
            }
            if (PUBLISH_HELM) {
                archiveArtifacts {
                    pattern("${BUILD_HELM_PATH}/*.tgz")
                    onlyIfSuccessful()
                }
            }
        }
    }

    // *******Create a build job for pull request merged************
    job("${PROJECT}.${REPOSITORY}.PR-MERGED.BUILD") {
        description('Builds in the devkit specified based on the bit bucket hook on the merging of Pull Request.')
        logRotator(-1, 5, -1, -1)
        basicBuildParameters(delegate, PROJECT, REPOSITORY)
        buildTriggerParameters(delegate)
        parameters {
            stringParam('DEVELOPMENT_BRANCH', "${DEVELOPMENT_BRANCH}", 'The GIT branch used for development.')
            stringParam('REPO_TYPE', 'dev', 'Use all of snapshots, releases and 3rd party repositories from Artifactory for finding the dependencies unless overriden to use only release repositories.')
            stringParam('ARTIFACTORY_REPOSITORIES', "${ARTIFACTORY_REPOSITORIES}", 'The repository to which artifact gets published to in artifactory.')
            stringParam('YUM_REPO_NAME', '', 'Parameter to be passed to the PUBLISH_TO_RPM.')
        }
        label('pr-merged-builds')
        gitCheckout(delegate, PROJECT, REPOSITORY, 'DESTINATION_BRANCH')
        wrappers {
            withSonarQubeEnv {
                installationName(SONAR_SERVER_PROD)
            }
            buildName('#${BUILD_NUMBER}, PR from: ${SOURCE_BRANCH}. PR to: ${DESTINATION_BRANCH}.')
        }
        if (PUBLISH_ARTIFACT) {
            wrappers {
                artifactoryUpload(delegate)
                buildName('#${BUILD_NUMBER}, Publishing ${artifactVersionNumber} version to ${ARTIFACTORY_REPOSITORIES}')
                credentialsBinding {
                    usernamePassword('ARTIFACTORY_PUBLISHER', 'artifactory_publisher')
                }
            }
        }
        checkForBuildTools(delegate)
        enableCodeCoverage(delegate, '\${DESTINATION_BRANCH}')
        injectEnvironmentalVariables(delegate)
        prMergeSonarProperties(delegate)
        buildArtifacts(delegate)
        // reload the env vars because buildArtifacts can update the version number
        // when prepping for a release.
        injectEnvironmentalVariables(delegate)
        buildRpms(delegate)
        sonarScanPRM(delegate, SONAR_SERVER_PROD, '\${DESTINATION_BRANCH}', '\${REPOSITORY}', '\${SQ_OVERRIDE_BRANCH}', '\${OVERRIDE_DESTINATION_BRANCH}')
        publishArtifactsPrep(delegate, ARTIFACTORY_REPOSITORIES)
        buildDockerImages(delegate)
        buildHelmCharts(delegate)
        runDockerCompose(delegate)
        if (PUBLISH_RPM) {
            steps {
                remoteTrigger('Search Jenkins', 'rpm-repo-publish') {
                    parameters(TRIGGERED_FROM_BUILD_JENKINS: 'true', RPMS_PATH: '${WORKSPACE}/' + BUILD_RPMS_PATH, YUM_REPO_NAME: '${YUM_REPO_NAME}')
                    preventRemoteBuildQueue(true)
                    blockBuildUntilComplete(true)
                }
            }
        }
        publishDockerImages(delegate)
        cleanDockerImages(delegate)
        deployDockerImages(delegate)
        publishers {
            stashNotifier()
            publishHtml(delegate, UNIT_HTML_REPORT)
            publishHtml(delegate, COVERAGE_HTML_REPORT)
            publishJunitTestResultReport(delegate, UNIT_XML_REPORT)
            postBuildEmails(delegate, "${DISTRIBUTION_LIST}")
            if (PUBLISH_RPM) {
                archiveArtifacts {
                    pattern("${BUILD_RPMS_PATH}/*/*.rpm")
                    onlyIfSuccessful()
                }
            }
            if (PUBLISH_HELM) {
                archiveArtifacts {
                    pattern("${BUILD_HELM_PATH}/*.tgz")
                    onlyIfSuccessful()
                }
                publishHelmCharts(delegate)
            }
            if (PUBLISH_ARTIFACT) {
                postBuildTask {
                    task('0 artifacts were found', 'exit 1', true, true)
                    task('Deploying artifact:', '''# Delete snapshots if the artifacts are published to release repo.
if [ "${REPO_TYPE}" == "release" ]; then
    sh deleteSnapshots.sh
    rm deleteSnapshots.sh
fi''', true, true)
                }
            }

        }
        updateNpmSnapshot(delegate)
    }

    // ********Create a build job for release request***********
    job("${PROJECT}.${REPOSITORY}.REL-SUPPORT.BUILD") {
        description('Builds in the devkit specified based on the bit bucket hook on the opening of Pull Request.')
        logRotator(-1, 5, -1, -1)
        basicBuildParameters(delegate, PROJECT, REPOSITORY)
        parameters {
            stringParam('SOURCE_BRANCH', '', 'The GIT branch from which the pull request is initiated.')
            stringParam('DEVELOPMENT_BRANCH', "${DEVELOPMENT_BRANCH}", 'The GIT branch used for development.')
            stringParam('REPO_TYPE', 'dev', 'Use all of snapshots, releases and 3rd party repositories from Artifactory for finding the dependencies unless overriden to use only release repositories.')
            stringParam('ARTIFACTORY_REPOSITORIES', "${ARTIFACTORY_REPOSITORIES}", 'The repository to which artifact gets published to in artifactory.')
        }
        label('pr-opened-builds')
        gitCheckout(delegate, PROJECT, REPOSITORY, 'SOURCE_BRANCH')
        wrappers {
            buildName('#${BUILD_NUMBER}, from: ${SOURCE_BRANCH}.')
        }
        if (PUBLISH_ARTIFACT) {
            wrappers {
                artifactoryUpload(delegate)
                buildName('#${BUILD_NUMBER}, Publishing ${artifactVersionNumber} version to ${ARTIFACTORY_REPOSITORIES}')
                credentialsBinding {
                    usernamePassword('ARTIFACTORY_PUBLISHER', 'artifactory_publisher')
                }
            }
        }
        checkForBuildTools(delegate)
        injectEnvironmentalVariables(delegate)
        buildArtifacts(delegate)
        buildRpms(delegate)
        buildDockerImages(delegate)
        buildHelmCharts(delegate)
        runDockerCompose(delegate)
        cleanDockerImages(delegate)
        publishArtifactsPrep(delegate, ARTIFACTORY_REPOSITORIES)
        publishers {
            if (PUBLISH_ARTIFACT) {
                postBuildTask {
                    task('0 artifacts were found', 'exit 1', true, true)
                    task('Deploying artifact:', '''# Delete snapshots if the artifacts are published to release repo.
if [ "${REPO_TYPE}" == "release" ]; then
    sh deleteSnapshots.sh
    rm deleteSnapshots.sh
fi''', true, true)
                }
            }
        }
    }


    // *******Create a build job for building branches and publishing docker images before pull request************
    job("${PROJECT}.${REPOSITORY}.PRE-PR.BUILD") {
        description('Builds in the docker images for any branch before creating pull requests.')
        logRotator(-1, 5, -1, -1)
        basicBuildParameters(delegate, PROJECT, REPOSITORY)
        parameters {
            stringParam('BRANCH', '', 'The GIT branch to be built.')
            stringParam('DEVELOPMENT_BRANCH', "${DEVELOPMENT_BRANCH}", 'The GIT branch used for development.')
            stringParam('REPO_TYPE', 'dev', 'Use all of snapshots, releases and 3rd party repositories from Artifactory for finding the dependencies unless overriden to use only release repositories.')
            stringParam('registry', 'registry.domain.com:5000', 'The internal docker registry.')
        }
        label('pre-pr-builds')
        gitCheckout(delegate, PROJECT, REPOSITORY, 'BRANCH')
        wrappers {
            buildName('#${BUILD_NUMBER}, Building branch: ${BRANCH}.')
        }
        checkForBuildTools(delegate)
        enableCodeCoverage(delegate, '\${SOURCE_BRANCH}')
        injectEnvironmentalVariables(delegate)
        buildRpms(delegate)
        buildDockerImages(delegate)
        buildHelmCharts(delegate)
        runDockerCompose(delegate)
        cleanDockerImages(delegate)
        publishers {
            publishHtml(delegate, UNIT_HTML_REPORT)
            publishHtml(delegate, COVERAGE_HTML_REPORT)
            publishJunitTestResultReport(delegate, UNIT_XML_REPORT)
            postBuildEmails(delegate, "${DISTRIBUTION_LIST}")
            if (PUBLISH_RPM) {
                archiveArtifacts {
                    pattern("${BUILD_RPMS_PATH}/*/*.rpm")
                    onlyIfSuccessful()

                }
            }
            if (PUBLISH_HELM) {
                archiveArtifacts {
                    pattern("${BUILD_HELM_PATH}/*.tgz")
                    onlyIfSuccessful()
                }
            }
        }
    }

    // *************Create manual SonarQube scan job**************
    job("${PROJECT}.${REPOSITORY}.MANUAL-SONARQUBE-SCAN") {
        description('Job to run manual SonarQube scans agains source code in Bitbucket.')
        logRotator(-1, 5, -1, -1)
        basicBuildParameters(delegate, PROJECT, REPOSITORY)
        parameters {
            stringParam('SOURCE_BRANCH', "", 'The GIT branch being scanned.')
            choiceParam('DESTINATION_BRANCH', ['master', 'develop'], 'Branch to which SOURCE_BRANCH will be merged.')
            stringParam('REPO_TYPE', 'dev', 'Use all of snapshots, releases and 3rd party repositories from Artifactory for finding the dependencies unless overriden to use only release repositories.')
            booleanParam('INITIAL_SONARQUBE_SCAN', false, 'Select ONLY for the initial SonarQube scan of the master or develop branch.  This establishes a baseline of issues found in the SonarQube projects (long lived branches).')
        }
        label('pre-pr-builds')
        gitCheckout(delegate, PROJECT, REPOSITORY, 'SOURCE_BRANCH')
        wrappers {
            withSonarQubeEnv {
                installationName(SONAR_SERVER_PROD)
            }
        }
        checkForBuildTools(delegate)
        injectEnvironmentalVariables(delegate)
        manualSonarProperties(delegate)
        //  injectEnvironmentalVariables(delegate)
        buildRpms(delegate)
        sonarScanMAN(delegate, SONAR_SERVER_PROD, '\${SOURCE_BRANCH}', '\${REPOSITORY}')
        publishers {
            postBuildEmails(delegate, "${DISTRIBUTION_LIST}")
        }
    }


    // **********Create a build job for veracode*****************
    if (BUILD_VERACODE) {
        job("${PROJECT}.${REPOSITORY}.VERACODE.SCAN") {
            description('Builds and performs veracode scan.')
            logRotator(-1, 5, -1, -1)
            basicBuildParameters(delegate, PROJECT, REPOSITORY)
            parameters {
                stringParam('DEVELOPMENT_BRANCH', "${DEVELOPMENT_BRANCH}", 'The GIT branch used for development.')
                stringParam('REPO_TYPE', 'dev', 'Use all of snapshots, releases and 3rd party repositories from Artifactory for finding the dependencies unless overriden to use only release repositories.')
            }
            label('veracode')
            gitCheckout(delegate, PROJECT, REPOSITORY, 'DEVELOPMENT_BRANCH')
            triggers {
                timerTrigger {
                    spec(VERACODE_SCHEDULE)
                }
            }
            wrappers {
                credentialsBinding {
                    usernamePassword(
                            'VERACODE_ID', 'VERACODE_KEY', VERACODE_CREDENTIALS_ID)
                }
            }
            checkForBuildTools(delegate)
            injectEnvironmentalVariables(delegate)
            steps {
                shell("# Build in devkit.\n${BUILD_TOOLS_PATH}/do-buildArtifact.sh")
            }
            publishers {
                postBuildEmails(delegate, "${DISTRIBUTION_LIST}")
                veracodeNotifier {
                    version('${PROJECT}-${REPOSITORY}-build-${BUILD_NUMBER}-at-${timestamp}')
                    appname("${REPOSITORY}") // name of application
                    createprofile(true) // if true, creates new application if matching application is not found
                    criticality('VeryHigh') // business criticality (VeryHigh, High, Medium, Low, VeryLow)
                    sandboxname('') // the name of the sandbox
                    createsandbox(false)
                    // creates new sandbox if sandbox name is provided and matching sandbox is not found
                    uploadincludespattern(VERACODE_INCLUDE_PATTERN)
                    // comma-separated list of ant-style include patterns (relative to job's workspace root directory) of files to upload
                    uploadexcludespattern(VERACODE_EXCLUDE_PATTERN)
                    // comma-separated list of ant-style exclude patterns
                    scanincludespattern('') // All uploaded files are included when omitted
                    scanexcludespattern('') // No uploaded files are excluded when omitted
                    filenamepattern('')
                    // filename pattern for names of the uploaded files that should be saved with a different name
                    replacementpattern('') // replacement pattern for groups captured by the filename pattern
                    waitForScan(true)
                    timeout(VERACODE_SCAN_TIMEOUT_MINUTES)
                    // submit the scan and wait the given amount of time (in minutes)
                    teams(VERACODE_TEAM_LIST)
                    credentials {
                        vapicredential('ID/Key')
                        vuser('${VERACODE_ID}')
                        vpassword('${VERACODE_KEY}')
                    }
                }
            }
        }
    }

    // ***********Create publish job for rpm registry***********************
    job("PUBLISH_TO_RPM") {
        description('Publishes the rpm to rpm repository.')
        logRotator(5, 100, -1, -1)
        parameters {
            booleanParam('REBUILD', true, 'Rebuild Entire YUM repo.')
            stringParam('RPMS_PATH', '', 'The path to the rpm.')
            choiceParam('YUM_REPO_NAME', ['il-devel', 'il-ci', 'il-stage', 'il-release', 'il-future'], 'Target YUM Repository Designation.')
        }
        label('pr-merged-builds')
        checkForBuildTools(delegate)
        steps {
            shell("# Publish to rpm repo.\nsudo -Eu jenkins ${BUILD_TOOLS_PATH}/do-publishToRpm.sh")
        }
    }

    // ***********Create publish job for PROMOTE_LIST_FILE_RPMS***********************
    job("PROMOTE_LIST_FILE_RPMS") {
        description('job for PROMOTE_LIST_FILE_RPMS.')
        logRotator(5, 100, -1, -1)
        buildTriggerParameters(delegate)
        parameters {
            stringParam('PROJECT', 'PLAT', 'The GIT Project key')
            stringParam('REPOSITORY', 'il-release-tools', 'The GIT repository')
        }
        label('pr-merged-builds')
        gitCheckout(delegate, PROJECT, REPOSITORY, 'DESTINATION_BRANCH')
        wrappers {
          buildName('#${BUILD_NUMBER}, PR from: ${SOURCE_BRANCH}. PR to: ${DESTINATION_BRANCH}.')
         }
        checkForBuildTools(delegate)
        getListDiff(delegate)
        injectEnvironmentalVariables(delegate)
steps {
        conditionalSteps {
          condition {
            expression('.*\\S.*', '${LIST_FILE_TO_CHECK}')
          }
          runner('DontRun')
        steps {
            remoteTrigger('Search Jenkins', 'rpm-repo-publish') {
                parameters(TRIGGER_PROMOTE_LIST_FILE_RPMS: 'true')
                parameters(LIST_FILE_TO_CHECK: '${LIST_FILE_TO_CHECK}')
                preventRemoteBuildQueue(true)
                blockBuildUntilComplete(true)
            }
        }
        }
        }
        publishers {
           stashNotifier()
           postBuildEmails(delegate, "${DISTRIBUTION_LIST}")
       }
    }

    // ***********Create publish job for helm repository***********************
    job("PUBLISH_TO_HELM") {
        description('Publishes the helm chart to helm repository.')
        logRotator(5, 100, -1, -1)
        parameters {
            stringParam('HELM_PATH', '', 'The path to the helm chart artifact.')
        }
        checkForBuildTools(delegate)
        steps {
            shell("# Publish to helm repo.\n${BUILD_TOOLS_PATH}/do-publishToHelm.sh")
        }
    }

    // *************Create master release job**************
    pipelineJob("${PROJECT}.${REPOSITORY}.RELEASE") {
        description('''
Performs git tagging and version number updates based on the option chosen.  Updating of the version number happens after
the tag has been applied on the current version. This process has the following rules:
<ul>
<li>You will be applying two tags. One will be '&lsaquo;currentVersionNumber&rsaquo;-RELEASE' the other will be based on VERSION_TO_UPDATE
<li>'&lsaquo;currentVersionNumber&rsaquo;-RELEASE' will be applied to "release" the current version.
<li>VERSION_TO_UPDATE will be applied to the build.properties and to mark the start of more work.
<li>You can only create a release branch from master.
<li>You can only update the patch version on release branches.
<li>When creating a release branch you can only update major version, minor version or override on the master branch.
<li>When creating the release branch do not use override to set update the patch version on the master branch.
<li>Override will allow you to set the next version to anything you want.  This means you can break your tagging by setting
   to a number from the past.
''')
        logRotator(2, 5, -1, -1)
        basicBuildParameters(delegate, PROJECT, REPOSITORY)
        parameters {

            stringParam('SOURCE_BRANCH', "${SOURCE_BRANCH}", 'The GIT branch to set release tag in.')
            stringParam('ARTIFACTORY_REPOSITORIES', "${ARTIFACTORY_REPOSITORIES}", 'The repository to which artifact gets published to in artifactory.')
            booleanParam('CREATE_RELEASE_BRANCH', false, '''check to create a release branch
Creates a release/1.2(.x) branch and increments the patch in the new branch.
In the master branch it will update the version in build.properties and the start tag based on the VERSION_TO_UPDATE chosen.
** Can only be executed if SOURCE_BRANCH is master **
''')
            choiceParam('VERSION_TO_UPDATE', ['', 'major', 'minor', 'patch', 'override'], '''
Version number will be incremented based on the options chosen.
The version number will be updated in the SOURCE_BRANCH after git tagging the current version with -RELEASE or -SNAPSHOT.
Example:
major     --> 1.2(.x).3 becomes 2.0(.0).1
minor     --> 1.2(.x).3 becomes 1.3(.0).1
patch     --> 1.2(.x).3 becomes 1.2(.x).4
override  --> 1.2(.x).3 becomes what you enter in OVERRIDE_VERSION below.
            ''')
            stringParam('OVERRIDE_VERSION', '', 'If you wish to set a specific version number after tagging, choose "override" above and enter the new number here.')
            booleanParam('DRY_RUN', false, "Don't execute anything, but validate and report back.")
        }
        releaseDefinition(delegate)
        configure {
            it / definition / lightweight(true)
        }
    }
}

//==================================CREATE RELEASE FAMILY METHOD===================================

// Method to create the release family job for each "project"
void createReleaseFamily(FAMILY) {
    println("Creating job: ${FAMILY}.FAMILY-RELEASE")
    job("${FAMILY}.FAMILY-RELEASE") {
        description('''
Performs git tagging and version number updates based on the option chosen.  Updating of the version number happens after
the tag has been applied on the current version. This process has the following rules:
<ul>
<li>You will be applying two tags. One will be '&lsaquo;currentVersionNumber&rsaquo;-RELEASE' the other will be based on VERSION_TO_UPDATE
<li>'&lsaquo;currentVersionNumber&rsaquo;-RELEASE' will be applied to "release" the current version.
<li>VERSION_TO_UPDATE will be applied to the build.properties and to mark the start of more work.
<li>You can only create a release branch from master.
<li>You can only update the patch version on release branches.
<li>When creating a release branch you can only update major version, minor version or override on the master branch.
<li>When creating the release branch do not use override to set update the patch version on the master branch.
<li>Override will allow you to set the next version to anything you want.  This means you can break your tagging by setting
   to a number from the past.
''')
        logRotator(2, 5, -1, -1)
        parameters {

            stringParam('SOURCE_BRANCH', "${SOURCE_BRANCH}", 'The GIT branch to set release tag in.')
            stringParam('ARTIFACTORY_REPOSITORIES', "${ARTIFACTORY_REPOSITORIES}", 'The repository to which artifact gets published to in artifactory.')
            booleanParam('CREATE_RELEASE_BRANCH', false, '''check to create a release branch
Creates a release/1.2(.x) branch and increments the patch in the new branch.
In the master branch it will update the version in build.properties and the start tag based on the VERSION_TO_UPDATE chosen.
** Can only be executed if SOURCE_BRANCH is master **
''')
            choiceParam('VERSION_TO_UPDATE', ['', 'major', 'minor', 'patch', 'override'], '''
Version number will be incremented based on the options chosen.
The version number will be updated in the SOURCE_BRANCH after git tagging the current version with -RELEASE or -SNAPSHOT.
Example:
major     --> 1.2(.x).3 becomes 2.0(.0).1
minor     --> 1.2(.x).3 becomes 1.3(.0).1
patch     --> 1.2(.x).3 becomes 1.2(.x).4
override  --> 1.2(.x).3 becomes what you enter in OVERRIDE_VERSION below.
            ''')
            stringParam('OVERRIDE_VERSION', '', 'If you wish to set a specific version number after tagging, choose "override" above and enter the new number here.')
            booleanParam('DRY_RUN', false, "Don't execute anything, but validate and report back.")
        }
        steps {
            downstreamParameterized {
                def jobList = ""
                int jobCount = 0
                jobLength = releaseFamily[FAMILY].size
                println("in ${FAMILY} there should be ${jobLength} elements ")
                releaseFamily[FAMILY].each {
                    projectAndRepoName = it
                    jobCount++
                    println("attaching trigger for: ${projectAndRepoName}.RELEASE")
                        jobList = "${projectAndRepoName}.RELEASE"
                        trigger(jobList) {
                            block {
                                unstable('UNSTABLE')
                            }
                            parameters {
                                predefinedProps(TRIGGERED_FROM_BUILD_JENKINS: 'true', CREATE_RELEASE_BRANCH: '${CREATE_RELEASE_BRANCH}',VERSION_TO_UPDATE: '${VERSION_TO_UPDATE}', SOURCE_BRANCH: '${SOURCE_BRANCH}', ARTIFACTORY_REPOSITORIES: '${ARTIFACTORY_REPOSITORIES}', DRY_RUN: '${DRY_RUN}', OVERRIDE_VERSION: '${OVERRIDE_VERSION}')
                            }
                        }
                    println("added ${jobCount} jobs")
                }
            }
            shell (
                '''
set +x
errorFlag=0
firstRun=0
regex="^.*[A-Z]+\\.(.+)\\.RELEASE .* ([0-9\\.]+) -> ([0-9\\.]+) .* (.+)$"
sourceName="../builds/${BUILD_NUMBER}/log"
fileName="./mylog"
cp ${sourceName} ${fileName}
while read -r line
do
if [[ $line =~ ${regex} ]];
then
    jobName="${BASH_REMATCH[1]}"
    V1="${BASH_REMATCH[2]}"
    V2="${BASH_REMATCH[3]}"
    resultCode="${BASH_REMATCH[4]}"
    if [[ ${firstRun} -eq 0 ]];
    then
        masterV1=${V1}
        masterV2=${V2}
        echo "master version numbers chosen: ${masterV1} ${masterV2}"
        firstRun=999
    else
        if [[ "${masterV1}" != "${V1}" || "${masterV2}" != "${V2}" ]];
        then
            echo "$jobName $V1 $V2 does not match up with master versions"
            errorFlag=1
        fi
    fi
    if [[ "${resultCode}" != "SUCCESS" ]];
    then
        echo "${jobName} returned ${resultCode} instead of SUCCESS.  You should look into why."
    fi
fi
done < ${fileName}
if [[ errorFlag -eq 1 ]];
then
    echo "issues found. Setting build failure."
    echo "Keep in mind, this is being set to get your attention"
    echo "inconsistency in version numbers may be expected."
    exit 1
fi
'''
            )
        }
        publishers {
            extendedEmail {
                if ("${DISTRIBUTION_LIST}" != "null") {
                    recipientList("${DISTRIBUTION_LIST}")
                }
                defaultSubject('${DEFAULT_SUBJECT}')
                defaultContent('BUILD URL: ${BUILD_URL}\nBUILD LOG:\n-----------\n${BUILD_LOG}')
                triggers {
                    unstable {
                        sendTo {
                            culprits()
                            developers()
                            failingTestSuspects()
                            firstFailingBuildSuspects()
                            recipientList()
                            requester()
                            upstreamCommitter()
                        }
                        attachBuildLog(true)
                    }
                    failure {
                        sendTo {
                            culprits()
                            developers()
                            failingTestSuspects()
                            firstFailingBuildSuspects()
                            recipientList()
                            requester()
                            upstreamCommitter()
                        }
                        attachBuildLog(true)
                    }
                }
            }
        }
    }
}

//==================================HELPER METHODS==============================================

def postBuildEmails(def context, DISTRIBUTION_LIST) {
    context.extendedEmail {
        if ("${DISTRIBUTION_LIST}" != "null") {
            recipientList("${DISTRIBUTION_LIST}")
        }
        defaultSubject('${DEFAULT_SUBJECT} (branch: ${GIT_BRANCH})')
        defaultContent('BUILD URL: ${BUILD_URL}\nBUILD LOG:\n-----------\n${BUILD_LOG}')
        triggers {
            failure {
                sendTo {
                    culprits()
                    developers()
                    failingTestSuspects()
                    firstFailingBuildSuspects()
                    recipientList()
                    requester()
                    upstreamCommitter()
                }
            }
            fixedUnhealthy {
                content('')
                subject('${JOB_NAME}:${GIT_BRANCH} is back to stable.')
                sendTo {
                    culprits()
                    developers()
                    failingTestSuspects()
                    firstFailingBuildSuspects()
                    recipientList()
                    requester()
                    upstreamCommitter()
                }
            }
        }
    }
}

def artifactoryUpload(def context) {
    context.artifactoryGenericConfigurator {
        details {
            artifactoryName('mvn_artifactory')
            artifactoryUrl(null)
            deployReleaseRepository(null)
            deploySnapshotRepository(null)
            resolveReleaseRepository(null)
            resolveSnapshotRepository(null)
            userPluginKey(null)
            userPluginParams(null)
        }
        resolverDetails(null)
        deployerCredentialsConfig(null)
        resolverCredentialsConfig(null)
        deployPattern('')
        resolvePattern('')
        matrixParams('')
        useSpecs(true)
        uploadSpec {
            spec('')
            filePath('uploadSpec.json')
        }
        downloadSpec {
            spec(null)
            filePath('')
        }
        deployBuildInfo(false)
        includeEnvVars(true)
        envVarsPatterns(null)
        discardOldBuilds(true)
        discardBuildArtifacts(true)
        multiConfProject(false)
        artifactoryCombinationFilter('')
        customBuildName('')
        overrideBuildName(false)
    }
}

def gitCheckout(def context, PROJECT, REPOSITORY, BRANCH) {
    context.scm {
        git {
            remote {
                url('ssh://git@stash.domain.com/${PROJECT}/${REPOSITORY}.git')
            }
            branch('${' + "${BRANCH}" + '}')
            extensions {
                localBranch('${' + "${BRANCH}" + '}')
                if(REPOSITORY == "il-brands") {
                    cleanBeforeCheckout()
                }
                else
                {
                    wipeOutWorkspace()
                }
            }
        }
    }
}

def basicBuildParameters(def context, PROJECT, REPOSITORY) {
    context.parameters {
        stringParam('PROJECT', "${PROJECT}", 'The project key specified for this project in GIT.')
        stringParam('REPOSITORY', "${REPOSITORY}", 'Name of the Repository.')
    }
}

def buildTriggerParameters(def context) {
    context.parameters {
        stringParam('SOURCE_BRANCH', '', 'The GIT branch from which the pull request is initiated.')
        stringParam('DESTINATION_BRANCH', '', 'The GIT branch to which the pull request will be merged.')
    }
}

def releaseDefinition(def context) {
    context.definition {
        cpsScm {
            scm {
                git {
                    remote {
                        url('ssh://git@stash.domain.com:7999//domain.git')
                    }
                    branch('master')
                    extensions {
                        wipeOutWorkspace()
                    }
                }
            }
            scriptPath('src/do-release.groovy')
        }
    }
}

def sonarScanPRO(def context, def serverName, SOURCE_BRANCH, REPOSITORY) {
    if(BUILD_SONAR) {
        context.steps {
            sonarRunnerBuilder {
                installationName(serverName)
                sonarScannerName('')
                properties('sonar.projectVersion=${artifactVersionNumber}\nsonar.projectKey=${REPOSITORY}\nsonar.projectName=${REPOSITORY}')
                javaOpts('')
                jdk('(Inherit From Job)')
                task('')
                additionalArguments('')
                project('./sonar-project.properties')
            }
             shell('''# Check for 0 scanned files.
wget ${BUILD_URL}/consoleText
if [ \"$(grep -c \"INFO: 0 files indexed\" consoleText)\" -gt 0 ]; then
  echo \"Sonar failed due to 0 files indexed! Please check the Sonar settings from your project.\"
  exit 1
fi\n
# Verify sonar quality gate.\n'''+
"${BUILD_TOOLS_PATH}"+'''/verifySonarQualityGateBranchMan.sh -b ${SOURCE_BRANCH} -p ${REPOSITORY}''')
        }
    }
}

def sonarScanPRM(def context, def serverName, DESTINATION_BRANCH, REPOSITORY, SQ_OVERRIDE_BRANCH, OVERRIDE_DESTINATION_BRANCH) {
    if(BUILD_SONAR) {
        context.steps {
            sonarRunnerBuilder {
                installationName(serverName)
                sonarScannerName('')
                properties('sonar.projectVersion=${artifactVersionNumber}\nsonar.projectKey=${REPOSITORY}\nsonar.projectName=${REPOSITORY}')
                javaOpts('')
                jdk('(Inherit From Job)')
                task('')
                additionalArguments('')
                project('./sonar-project.properties')
            }
             shell("# Verify sonar quality gate.\n#Default setting.\nSQ_OVERRIDE_BRANCH=\"false\"\n\n# Read build.properties file for SonarQube override setting.\nsource ./build.properties\n\n# SQ_OVERRIDE_BRANCH = true\nif [ ${SQ_OVERRIDE_BRANCH} = \"true\" ]; then\n\n\t# master or develop branch logic\n\tif [ ${DESTINATION_BRANCH} = \"master\" ] || [ ${DESTINATION_BRANCH} = \"develop\" ] ; then\n\t\t${BUILD_TOOLS_PATH}/verifySonarQualityGateBranchMan.sh -b ${DESTINATION_BRANCH} -p ${REPOSITORY}\n\t\texit 0\n\tfi\n\n\t# Not master or develop logic\n\tif [ ${DESTINATION_BRANCH} != \"master\" ] || [ ${DESTINATION_BRANCH} != \"develop\" ] ; then\n\t\t${BUILD_TOOLS_PATH}/verifySonarQualityGateBranchMan.sh -b ${OVERRIDE_DESTINATION_BRANCH} -p ${REPOSITORY}\n\t\texit 0\n\tfi\nfi\n\n# SQ_OVERRIDE_BRANCH = true\nif [ ${SQ_OVERRIDE_BRANCH} = \"false\" ]; then\n\n\t# master or develop logic\n\tif [ ${DESTINATION_BRANCH} = \"master\" ] || [ ${DESTINATION_BRANCH} = \"develop\" ] ; then\n\t\t${BUILD_TOOLS_PATH}/verifySonarQualityGateBranchMan.sh -b ${DESTINATION_BRANCH} -p ${REPOSITORY}\n\t\texit 0\n\tfi\n\n\t# Not master or develop logic (all other branches, use master)\n\tif [ ${DESTINATION_BRANCH} != \"master\" ] && [ ${DESTINATION_BRANCH} != \"develop\" ]; then\n\t\t${BUILD_TOOLS_PATH}/verifySonarQualityGateBranchMan.sh -b master  -p ${REPOSITORY}\n\t\texit 0\n\tfi\nfi")
        }
    }
}

def sonarScanMAN(def context, def serverName, SOURCE_BRANCH, REPOSITORY) {
    if(BUILD_SONAR) {
        context.steps {
            sonarRunnerBuilder {
                installationName(serverName)
                sonarScannerName('')
                properties('sonar.projectVersion=${artifactVersionNumber}\nsonar.projectKey=${REPOSITORY}\nsonar.projectName=${REPOSITORY}')
                javaOpts('')
                jdk('(Inherit From Job)')
                task('')
                additionalArguments('')
                project('./sonar-project.properties')
            }
            shell("# Verify sonar quality gate.\n${BUILD_TOOLS_PATH}/verifySonarQualityGateBranchMan.sh -b ${SOURCE_BRANCH} -p ${REPOSITORY}")
        }
    }
}



def publishHtml(def context, HTML_REPORT) {
    if ("${HTML_REPORT}" != "null") {
        context.publishHtml {
            Map<String, Object> htmlReportMap = new HashMap<String, Object>();
            HTML_REPORT.each { k, v ->
                htmlReportMap.put(k, v)
            }
            report(htmlReportMap.get("reportDir")) {
                reportFiles(htmlReportMap.get("reportFile"))
                reportName(htmlReportMap.get("reportTitle"))
                alwaysLinkToLastBuild(true)
            }
        }
    }
}

def publishJunitTestResultReport(def context, XML_REPORT) {
    if ("${XML_REPORT}" != "null") {
        context.jUnitResultArchiver {
            Map<String, Object> junitReportMap = new HashMap<String, Object>();
            XML_REPORT.each { k, v ->
                junitReportMap.put(k, v)
            }
            testResults(junitReportMap.get("reportDir"))
            healthScaleFactor(UNIT_TEST_RESULTS_HEALTH_SCALE_FACTOR)
            allowEmptyResults(true)
        }
    }
}

def buildArtifacts(def context) {
    if (BUILD_ARTIFACT) {
        context.steps {
            shell("# Build in devkit.\n${BUILD_TOOLS_PATH}/do-buildArtifact.sh")
        }
    }
}

def buildRpms(def context) {
    if (BUILD_RPM) {
        context.steps {
            shell("# Build in rpm devkit.\n${BUILD_TOOLS_PATH}/il-build-ctl rpm-build")
        }
    }
}

def buildDockerImages(def context) {
    if (BUILD_DOCKER) {
        context.steps {
            shell("# Build docker images.\n${BUILD_TOOLS_PATH}/il-build-ctl docker-build")
        }
    }
}

def buildHelmCharts(def context) {
    if (BUILD_HELM) {
        context.steps {
            shell("# Build helm charts.\n${BUILD_TOOLS_PATH}/il-build-ctl helm-build")
        }
    }
}

def publishArtifactsPrep(def context, repositories) {
    if (PUBLISH_ARTIFACT) {
        context.steps {
            shell("# Publish to artifactory.\n${BUILD_TOOLS_PATH}/do-publishToArtifactory-new.sh " + repositories)
        }
    }
}

def publishDockerImages(def context) {
    if (PUBLISH_DOCKER) {
        context.steps {
            shell("# Publish docker images.\n${BUILD_TOOLS_PATH}/il-build-ctl docker-push")
        }
    }
}

def publishHelmCharts(def context) {
    if (PUBLISH_HELM) {
        context.downstreamParameterized {
            trigger("PUBLISH_TO_HELM") {
                condition('SUCCESS')
                parameters {
                    predefinedProp("HELM_PATH", '${JENKINS_HOME}/jobs/${JOB_NAME}/builds/${BUILD_NUMBER}/archive/dist/build/helm')
                    currentBuild()
                }
            }
        }

    }
}

def cleanDockerImages(def context) {
    if (BUILD_DOCKER) {
        context.steps {
            if (RUN_DOCKER_COMPOSE) {
                shell("# Clean up docker compose. \ndocker-compose -f dist/docker-compose.yml down --rmi all")
            }
            shell("# Clean up docker images.\n${BUILD_TOOLS_PATH}/il-build-ctl docker-clean")
        }
    }
}

def runDockerCompose(def context) {
    if (RUN_DOCKER_COMPOSE) {
        context.steps {
            shell("# Run docker-compose.\ndocker-compose -f dist/docker-compose.yml up")
        }
    }
}

def deployDockerImages(def context) {
    if (DEPLOY_DOCKER) {
        context.steps {
            remoteTrigger('Search Jenkins', '${PROJECT}.${REPOSITORY}.deploy') {
                parameter('VERSION', '${artifactVersionNumber}')
            }
        }
    }
}

def enableCodeCoverage(def context, BRANCH) {
    context.steps {
       shell("set -x;ENV_PROPERTIES_FILE=${ENV_PROPERTIES_FILE}\n"+
        '''
# Adding property to enable code coverage.
echo -e '\\ncodeCoverage=true' >> ${ENV_PROPERTIES_FILE}
#Adding YUM_REPO_NAME parameter to be passed to the PUBLISH_TO_RPM.
[[ $(grep -o 'YUM_REPO_NAME=.\\+$' ${ENV_PROPERTIES_FILE}) ]] || YUM_REPO_NAME=il-devel
[[ ${DESTINATION_BRANCH} =~ "release" ]] && YUM_REPO_NAME=il-release
[[ ${DESTINATION_BRANCH} =~ "master" ]] && YUM_REPO_NAME=il-devel
[[ ! -z ${YUM_REPO_NAME} ]] && echo -e "\\nYUM_REPO_NAME=${YUM_REPO_NAME}">> ${ENV_PROPERTIES_FILE} || true
'''
        )
    }
}

def injectEnvironmentalVariables(def context) {
    context.steps {
        envInjectBuilder {
            propertiesFilePath(ENV_PROPERTIES_FILE)
            propertiesContent('')
        }
    }
}

def checkForBuildTools(def context) {
    context.steps {
        shell('''# Check for 
if [ ! -d "/opt/il/share/il/build-tools" ]; then
    echo "Cannot find build-tools. Please install!!"
    exit 1
fi
        ''')
    }
}

def getListDiff(def context) {
    context.steps {
        shell('''########### Get list of diff  ##############
JOB_URL="http://build.jenkins.domain.com:8080/job/PROMOTE_LIST_FILE_RPMS"
# CommitID for last successful jenkins build
lastSuccesscommitID=$(curl -sf "${JOB_URL}/lastSuccessfulBuild/api/xml?xpath=//lastBuiltRevision/SHA1" | sed 's/<[^>]*>//g')
# CommitID for current build workspace
commitID=$(git rev-parse HEAD)
echo "ID +++ ===  ${lastSuccesscommitID}     	${commitID} "
# List under versionlock directory in changeset above
changeset_in_src=$(git diff --diff-filter=ACM ${lastSuccesscommitID}  ${commitID}   --name-only | \\
                  grep "^versionlock\\/.*\\.list" | awk -F"/" {'print $2'} | uniq)
excludeArray=(versionlock/empty.list versionlock/current.list versionlock/devel.list versionlock/release.list versionlock/future.list versionlock/archives/*)
allFiles=($changeset_in_src)
listArray=()
IFS=/
for file in "${allFiles[@]}"; do
    case "/${excludeArray[*]}/" in
        (*"/$file/"*) ;;     # do nothing (exclude)
        (*) listArray+=("${file:0:${#file}-5}")  # add to the array    without the last 4 chars ( .list )
    esac
done
LIST_FILE_TO_CHECK=${listArray[@]}
# add lists to ENV variables file
set -x;ENV_PROPERTIES_FILE=build.properties
echo -e 'LIST_FILE_TO_CHECK='${LIST_FILE_TO_CHECK} > ${ENV_PROPERTIES_FILE}
echo "List of files parsed to the rpm-repo-publish: " ${LIST_FILE_TO_CHECK}
        ''')
    }
}

def prOpenSonarProperties(def context) {
    if(BUILD_SONAR) {
        context.steps {
            shell('''# SonarQube property definitions based on destination branch scans.
#Default setting.
SONARQUBE_PROPERTIES_FILE=sonar-project.properties
SQ_OVERRIDE_BRANCH="false"
source ./build.properties
echo "SonarQube override branch setting = ${SQ_OVERRIDE_BRANCH}"
# Check for sonar-project.properties
#
if [ ! -f "${SONARQUBE_PROPERTIES_FILE}" ]; then
    echo "${SONARQUBE_PROPERTIES_FILE} need to be present in the root of the repository for repositories with SONAR enabled."
    exit 1
fi
# Subsequent SQ scans after master/develop project created.
#
if [ ${SQ_OVERRIDE_BRANCH} = "false" ]; then
        # SQ scan if DESTINATION_BRANCH is master or develop
        #
        if [ ${DESTINATION_BRANCH} = "master" ] || [ ${DESTINATION_BRANCH} = "develop" ] ; then
                echo "SonarQube properties file update for ${DESTINATION_BRANCH} branch destination scan."
                echo -e "\\nsonar.branch.name=${SOURCE_BRANCH}" >> ${SONARQUBE_PROPERTIES_FILE}
                echo -e "sonar.branch.target=${DESTINATION_BRANCH}" >> ${SONARQUBE_PROPERTIES_FILE}
                exit 0
        fi
	# SQ scan if DESTINATION_BRANCH is not master, not develop or release, default to master for target branch.
	#
	pat="(release\\/[0-9]|[1-9][0-9]\\.[0-9]|[1-9][0-9]\\.[0-9]|[1-9][0-9])"
	if [ ${DESTINATION_BRANCH} != "master" ] || [ ${DESTINATION_BRANCH} != "develop" ] || [[ ${DESTINATION_BRANCH} =~ $pat ]] ; then
                echo "SonarQube properties file update for ${DESTINATION_BRANCH} branch destination scan."
                echo -e "\\nsonar.branch.name=${SOURCE_BRANCH}" >> ${SONARQUBE_PROPERTIES_FILE}
                echo -e "sonar.branch.target=master" >> ${SONARQUBE_PROPERTIES_FILE}
                exit 0
        fi
        # SQ scan if DESTINATION_BRANCH is release/X.X.X
        #
        #pat="(release\\/[0-9]|[1-9][0-9]\\.[0-9]|[1-9][0-9]\\.[0-9]|[1-9][0-9])"
        #if [[ ${DESTINATION_BRANCH} =~ $pat ]] ; then
        #        echo "SonarQube properties file update for release, ${DESTINATION_BRANCH} branch destination scan."
        #        echo -e "\\nsonar.branch.name=${SOURCE_BRANCH}" >> ${SONARQUBE_PROPERTIES_FILE}
        #        echo -e "sonar.branch.target=master" >> ${SONARQUBE_PROPERTIES_FILE}
        #        exit 0
        #fi
fi
if [ ${SQ_OVERRIDE_BRANCH} = "true" ]; then
        # SQ scan if DESTINATION_BRANCH is master or develop.
        #
        if [ ${DESTINATION_BRANCH} = "master" ] || [ ${DESTINATION_BRANCH} = "develop" ]; then
                echo "SonarQube properties file update for default  master branch scan."
                echo -e "\\nsonar.branch.name=${SOURCE_BRANCH}" >> ${SONARQUBE_PROPERTIES_FILE}
                echo -e "sonar.branch.target=${DESTINATION_BRANCH}" >> ${SONARQUBE_PROPERTIES_FILE}
                exit 0
        fi
        # SQ scan if DESTINATION_BRANCH is NOT master or develop.
        #
        if [ ${DESTINATION_BRANCH} != "master" ] || [ ${DESTINATION_BRANCH} != "develop" ]; then
                echo "SonarQube properties file update for ${OVERRIDE_DESTINATION_BRANCH} override branch scan."
                echo -e "\\nsonar.branch.name=${SOURCE_BRANCH}" >> ${SONARQUBE_PROPERTIES_FILE}
                echo -e "sonar.branch.target=${OVERRIDE_DESTINATION_BRANCH}" >> ${SONARQUBE_PROPERTIES_FILE}
                exit 0
        fi
fi
# Error if user manual enters incorrect destination branch and SQ options
#
echo "Incorrect use of destination branch and/or SQ options. See SCM team for help."
exit 1
            ''')
        }
    }
}

def prMergeSonarProperties(def context) {
    if(BUILD_SONAR) {
        context.steps {
            shell('''# SonarQube property definitions based on destination branch scans.
#Default setting.
SONARQUBE_PROPERTIES_FILE=sonar-project.properties
SQ_OVERRIDE_BRANCH="false"
source ./build.properties
echo "SonarQube override branch setting = ${SQ_OVERRIDE_BRANCH}"
# Check for sonar-project.properties
#
if [ ! -f "${SONARQUBE_PROPERTIES_FILE}" ]; then
    echo "${SONARQUBE_PROPERTIES_FILE} need to be present in the root of the repository for repositories with SONAR enabled."
    exit 1
fi
# Subsequent SQ scans after master/develop project created.
#
if [ ${SQ_OVERRIDE_BRANCH} = "false" ]; then
	# SQ scan if DESTINATION_BRANCH is master or develop
	#
	if [ ${DESTINATION_BRANCH} = "master" ] || [ ${DESTINATION_BRANCH} = "develop" ] ; then
		echo "SonarQube properties file update for ${DESTINATION_BRANCH} branch destination scan."
		echo -e "\nsonar.branch.name=${DESTINATION_BRANCH}" >> ${SONARQUBE_PROPERTIES_FILE}
		exit 0
	fi
	# SQ scan if DESTINATION_BRANCH is release/X.X.X
	#
	pat="(release\\/[0-9]|[1-9][0-9]\\.[0-9]|[1-9][0-9]\\.[0-9]|[1-9][0-9])"
	if [[ ${DESTINATION_BRANCH} =~ $pat ]] ; then
		echo "SonarQube properties file update for release, ${DESTINATION_BRANCH} branch destination scan."
		echo -e "\nsonar.branch.name=master" >> ${SONARQUBE_PROPERTIES_FILE}
		exit 0
	fi
    # SQ scan if DESTINATION_BRANCH is NOT master or develop and SQ override is false
	#
	if [ ${DESTINATION_BRANCH} != "master" ] && [ ${DESTINATION_BRANCH} != "develop" ] ; then
		echo "SonarQube properties file update for default  master branch scan."
		echo -e "\nsonar.branch.name=master" >> ${SONARQUBE_PROPERTIES_FILE}
		exit 0
	fi
fi
# SQ scan if DESTINATION_BRANCH is NOT master or develop and SQ override is true
#
if [ ${SQ_OVERRIDE_BRANCH} = "true" ]; then
	if [ ${DESTINATION_BRANCH} = "master" ] || [ ${DESTINATION_BRANCH} = "develop" ] ; then
		echo "SonarQube properties file update for ${OVERRIDE_DESTINATION_BRANCH} override branch scan."
		echo -e "\nsonar.branch.name=${DESTINATION_BRANCH}" >> ${SONARQUBE_PROPERTIES_FILE}
		exit 0
	fi
	if [ ${DESTINATION_BRANCH} != "master" ] && [ ${DESTINATION_BRANCH} != "develop" ] ; then
		echo "SonarQube properties file update for ${OVERRIDE_DESTINATION_BRANCH} override branch scan."
		echo -e "\nsonar.branch.name=${OVERRIDE_DESTINATION_BRANCH}" >> ${SONARQUBE_PROPERTIES_FILE}
		exit 0
	fi
fi
# Error if user manual enters incorrect destination branch and SQ options
#
echo "Incorrect use of destination branch and/or SQ options. See SCM team for help."
exit 1
            ''')
        }
    }
}

def manualSonarProperties(def context) {
    if(BUILD_SONAR) {
        context.steps {
            shell('''# SonarQube property definitions based on destination branch scans.
SONARQUBE_PROPERTIES_FILE=sonar-project.properties
# Check for sonar-project.properties
#
if [ ! -f "${SONARQUBE_PROPERTIES_FILE}" ]; then
    echo "${SONARQUBE_PROPERTIES_FILE} need to be present in the root of the repository for repositories with SONAR enabled."
    exit 1
fi
# Initial scans for master/develop branch in SonarQube.
# If master branch scan, no need to add any parameters to ${SONARQUBE_PROPERTIES_FILE}.
#
if [ ${INITIAL_SONARQUBE_SCAN} = "true" ]; then
	if [ ${DESTINATION_BRANCH} = "develop" ]  ; then
        	echo "SonarQube properties file update for initial develop branch scan."
        	echo -e "\\nsonar.branch.name=${DESTINATION_BRANCH}" >> ${SONARQUBE_PROPERTIES_FILE}
        	exit 0
        fi
        if [ ${DESTINATION_BRANCH} = "master" ]  ; then
                echo "SonarQube properties file, not updated as initial scan for master branch."
                exit 0
        fi
fi
# Subsequent SQ scans after master/develop project created.
#
if [ ${INITIAL_SONARQUBE_SCAN} = "false" ]; then
        # SQ scan if DESTINATION_BRANCH is master or develop
        #
        if [ ${DESTINATION_BRANCH} = "master" ] || [ ${DESTINATION_BRANCH} = "develop" ] ; then
		if [ ${SOURCE_BRANCH} = "master" ] || [ ${SOURCE_BRANCH} = "develop" ] ; then
                	echo "SonarQube properties file update for ${DESTINATION_BRANCH} branch destination scan."
                	echo -e "\nsonar.branch.name=${DESTINATION_BRANCH}" >> ${SONARQUBE_PROPERTIES_FILE}
                	exit 0
        	fi
		if [ ${DESTINATION_BRANCH} != "master" ] || [ ${DESTINATION_BRANCH} != "develop" ] ; then
		        echo "SonarQube properties file update for ${DESTINATION_BRANCH} branch destination scan."
                	echo -e "\\nsonar.branch.name=${SOURCE_BRANCH}" >> ${SONARQUBE_PROPERTIES_FILE}
			echo -e "sonar.branch.target=${DESTINATION_BRANCH}" >> ${SONARQUBE_PROPERTIES_FILE}
                	exit 0
        	fi
	fi
fi
# Error if user manual enters incorrect destination branch and SQ options
#
echo "Manual scan performed using incorrect use of destination branch and/or SQ options. See SCM team for help."
exit 1
            ''')
        }
    }
}

def updateNpmSnapshot(def context) {
    context.steps {
        shell("# Update the version if -pre-release version numbers are in use.\nset -x\n${BUILD_TOOLS_PATH}/updateNpmVersion.sh -b \"\${DESTINATION_BRANCH}\" -m \"JENKINS POST-MERGE VERSION UPDATE - \"")
    }
}

def generateListView(def context, viewName, expression) {
    context.listView(viewName) {
        description("All jobs related to ${viewName}.")
        filterBuildQueue(true)
        filterExecutors(true)
        jobs {
            regex(expression)
        }
        columns {
            status()
            name()
            lastSuccess()
            lastFailure()
            lastDuration()
            buildButton()
        }
    }
}
