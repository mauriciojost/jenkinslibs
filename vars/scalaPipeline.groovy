def call(Map params) {

  // https://jenkins.io/doc/book/pipeline/jenkinsfile/
  // Scripted pipeline (not declarative)
  pipeline {
    triggers {
      pollSCM '* * * * *'
    }
    options {
      buildDiscarder(logRotator(numToKeepStr: params['buildsToKeep']))
      disableConcurrentBuilds()
    }
    agent any // run in current physical node unless told otherwise
    
    stages {
      stage('Full fetch') {
        steps {
          sh 'pwd'
          sh 'hostname'
          sh 'git fetch --depth=10000'
          sh 'git fetch --tags'
        }
      }

      stage('Test / coverage / package') {
        agent {
          docker { 
            image params['dockerImage']
            args params['dockerArgs']
          }
        }
        steps {
          wrap([$class: 'AnsiColorBuildWrapper', 'colorMapName': 'gnome-terminal']) {
            timeout(time: params['timeoutMinutes'], unit: 'MINUTES') {
              sh 'pwd'
              sh 'hostname'
              echo "My branch is: ${env.BRANCH_NAME}"
              sh 'sbt -Dsbt.color=always -Dsbt.global.base=.sbt -Dsbt.boot.directory=.sbt -Dsbt.ivy.home=.ivy2 clean "set every coverageEnabled := true" test coverageReport'
            sh 'sbt -Dsbt.color=always -Dsbt.global.base=.sbt -Dsbt.boot.directory=.sbt -Dsbt.ivy.home=.ivy2 coverageAggregate'
            sh 'sbt -Dsbt.color=always -Dsbt.global.base=.sbt -Dsbt.boot.directory=.sbt -Dsbt.ivy.home=.ivy2 universal:packageBin'
          }
          step([$class: 'ScoveragePublisher', reportDir: 'target/scala-2.12/scoverage-report', reportFile: 'scoverage.xml'])
        }
      }
      stage('Publish') {
        when {
          expression { params['package'] != null }
        }
        steps {
          wrap([$class: 'AnsiColorBuildWrapper', 'colorMapName': 'gnome-terminal']) {
            sh 'mv target/universal/*.zip ' + params['package']
          }
        }
      }
    }
    post {  
      failure {  
        emailext body: "<b>[JENKINS] Failure</b>Project: ${env.JOB_NAME} <br>Build Number: ${env.BUILD_NUMBER} <br> Build URL: ${env.BUILD_URL}", from: '', mimeType: 'text/html', replyTo: '', subject: "ERROR CI: ${env.JOB_NAME}", to: params['email'], attachLog: true, compressLog: false;
      }  
      success {  
        emailext body: "<b>[JENKINS] Success</b>Project: ${env.JOB_NAME} <br>Build Number: ${env.BUILD_NUMBER} <br> Build URL: ${env.BUILD_URL}", from: '', mimeType: 'text/html', replyTo: '', subject: "SUCCESS CI: ${env.JOB_NAME}", to: params['email'], attachLog: false, compressLog: false;
      }  
    }
  }
}
