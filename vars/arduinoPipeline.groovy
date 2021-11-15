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

    stages {
      stage('Build & deliver') {
        agent { docker params['dockerImage'] }
        stages {
          stage('Context') {
            steps {
              script {
                sh 'platformio --version'
                sh 'platformio platform list'
                sh 'rm -fr .cicd && mkdir -p .cicd && cd .cicd && git clone https://github.com/mauriciojost/arduino-cicd.git'
              }
            }
          }
          stage('Scripts prepared') {
            steps {
              script {
                sshagent(['bitbucket_key']) {
                  sh 'git submodule update --init --recursive'
                  sh '.mavarduino/create_links'
                }
              }
            }
          }

          stage('Update build refs') {
            steps {
              script {
                def vers = sh(script: './upload -i', returnStdout: true)
                def buildId = env.BUILD_ID
                currentBuild.displayName = "#$buildId - $vers"
              }
            }
          }

          stage('Pull dependencies') {
            steps {
              script {
                sshagent(['bitbucket_key']) {
                  wrap([$class: 'AnsiColorBuildWrapper', 'colorMapName': 'xterm']) {
                    sh 'export GIT_COMMITTER_NAME=jenkinsbot && export GIT_COMMITTER_EMAIL=mauriciojostx@gmail.com && set && ./pull_dependencies -p -l'
                  }
                }
              }
            }
          }
            stage('Test') {
              when {
                expression { ! params.get('testDisabled', true) }
              }
              steps {
                wrap([$class: 'AnsiColorBuildWrapper', 'colorMapName': 'xterm']) {
                  sh './launch_tests'
                }
              }
            }
            stage('Simulate') {
              when {
                expression { ! params.get('simulationDisabled', true) }
              }
              steps {
                wrap([$class: 'AnsiColorBuildWrapper', 'colorMapName': 'xterm']) {
                  sh './simulate profiles/simulate.prof 1 10' 
                }
              }
            }
            stage('Artifact') {
              when {
                expression { ! params.get('artifactsDisabled', true) }
              }
              steps {
                wrap([$class: 'AnsiColorBuildWrapper', 'colorMapName': 'xterm']) {
                  sh '''#!/bin/bash
                    for platform in $(cat .platforms.build)
                    do
                      for feature in $(cat .feature_branches.build)
                      do
                        if [ "$feature" == "generic" ]
                        then
                          ./upload -n "$platform" -p "profiles/$feature.prof" -e
                        else
                          ./upload -n "$platform" -p "profiles/$feature.prof" -e -t "$feature"
                        fi
                      done
                    done
                  '''
              }
            }
          }
        }

        post {  
          success {  
            cobertura autoUpdateHealth: false, autoUpdateStability: false, coberturaReportFile: 'coverage.xml', conditionalCoverageTargets: '70, 0, 0', enableNewApi: true, failUnhealthy: false, failUnstable: false, lineCoverageTargets: '80, 0, 0', maxNumberOfBuilds: 0, methodCoverageTargets: '80, 0, 0', onlyStable: false, sourceEncoding: 'ASCII', zoomCoverageChart: false
          }  
        }

      }
      stage('Publish') {
        agent any
        stages {
          stage('Publish') {
            when {
              expression { ! params.get('publishDisabled', true) }
            }
            steps {
              wrap([$class: 'AnsiColorBuildWrapper', 'colorMapName': 'xterm']) {
                sh 'bash ./.cicd/arduino-cicd/expose_artifacts'
              }
            }
          }
        }
      }
    }

    agent any

    post {  
      failure {  
        emailext body: "<b>[JENKINS] Failure</b>Project: ${env.JOB_NAME} <br>Build Number: ${env.BUILD_NUMBER} <br> Build URL: ${env.BUILD_URL}", from: '', mimeType: 'text/html', replyTo: '', subject: "ERROR CI: ${env.JOB_NAME}", to: "mauriciojostx@gmail.com", attachLog: true, compressLog: false;
      }  
      success {  
        emailext body: "<b>[JENKINS] Success</b>Project: ${env.JOB_NAME} <br>Build Number: ${env.BUILD_NUMBER} <br> Build URL: ${env.BUILD_URL}", from: '', mimeType: 'text/html', replyTo: '', subject: "SUCCESS CI: ${env.JOB_NAME}", to: "mauriciojostx@gmail.com", attachLog: false, compressLog: false;
      }  
      always {
        deleteDir()
      }
    }
  }
}
