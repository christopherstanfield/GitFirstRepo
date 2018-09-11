import groovy.json.JsonSlurper

def project = 'DSL-Exemple'


    def jsonSlurper = new JsonSlurper()
    def reader = new BufferedReader(new InputStreamReader(new FileInputStream("seed.groovy"),"UTF-8"));
    data = jsonSlurper.parse(reader); 



data.TST.each {
    def branchName = it.repositoryName
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
