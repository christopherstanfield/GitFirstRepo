import groovy.json.JsonSlurper


// List of common views to be generated.
def commonViewsList = [ 'PRE-PR.BUILD', 'PR-OPENED.BUILD', 'PR-MERGED.BUILD', 'RELEASE',
                        'FAMILY-RELEASE', 'PUBLISH' , 'VERACODE.SCAN' , 'MANUAL-SONARQUBE-SCAN'  ]
File jsonFile = new File("${WORKSPACE}/${DSL_FILE}")
def dslJobs = new JsonSlurper().parse(jsonFile)

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
