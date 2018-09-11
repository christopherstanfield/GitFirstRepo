import groovy.json.JsonSlurper

//def project = 'quidryan/aws-sdk-test'
//def branchApi = new URL("https://api.github.com/repos/${project}/branches")

File jsonFile = new File("seed.groovy")
def branches =new JsonSlurper().parse(jsonFile)
//def branches = new groovy.json.JsonSlurper().parse(branchApi.newReader())
branches.each {
    def branchName = it.name
    def jobName = "${project}-${branchName}".replaceAll('/','-')
    job(jobName) {
        scm {
            git("git://github.com/${project}.git", branchName)
        }
        steps {
            maven("test -Dproject.name=${project}/${branchName}")
        }
    }
}
