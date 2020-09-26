def call(Map params) {

  String sbtOpts = "-Dsbt.color=always -Dsbt.global.base=.sbt -Dsbt.boot.directory=.sbt -Dsbt.ivy.home=.ivy2"
  String contextCmds = "hostname && date && pwd && ls -lah"
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
          sh "${contextCmds}"
          sh 'git fetch --depth=10000'
          sh 'git fetch --tags'
        }
      }

      stage('Test / coverage / package') {
        agent {
          docker { // in $HOME of docker the workspace of the running job is mounted
            image params['dockerImage']
            args params['dockerArgs']
          }
        }
        steps {
          wrap([$class: 'AnsiColorBuildWrapper', 'colorMapName': 'gnome-terminal']) {
            timeout(time: params['timeoutMinutes'], unit: 'MINUTES') {
              echo "My branch is: ${env.BRANCH_NAME}"
              sh "${contextCmds}"
              sh "sbt ${sbtOpts} clean \"set every coverageEnabled := true\" test coverageReport"
              sh "sbt ${sbtOpts} coverageAggregate"
            }
          }
          step([$class: 'ScoveragePublisher', reportDir: 'target/scala-2.12/scoverage-report', reportFile: 'scoverage.xml'])
          wrap([$class: 'AnsiColorBuildWrapper', 'colorMapName': 'gnome-terminal']) {
            timeout(time: params['timeoutMinutes'], unit: 'MINUTES') {
              sh "${contextCmds}"
              sh "sbt ${sbtOpts} universal:packageBin"
            }
          }
        }
      }
      stage('Publish') {
        when {
          expression { params['package'] != null }
        }
        steps {
          wrap([$class: 'AnsiColorBuildWrapper', 'colorMapName': 'gnome-terminal']) {
            sh "${contextCmds}"
            sh 'ls -lah target/universal'
            sh ('mv target/universal/*.zip ' + params['package'])
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
        archiveArtifacts artifacts: 'target/universal/*.zip', fingerprint: true
      }  
    }
  }
}
