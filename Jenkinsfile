@Library('paves-shared-lib') _

// CI (pre-merge) pipeline — checkout, compile, unit test, SonarQube, quality gate.
// See jenkins-shared-lib/vars/buildPipeline.groovy in intranet-devops for the stage list.
//
// NOTE: every other service's Jenkinsfile calling buildPipeline() defaults to jdk: 'jdk17'.
// This app is Java 21 (see pom.xml), so jdk21 is passed explicitly below — confirm a
// 'jdk21' tool is actually configured in Jenkins before relying on this (see the plan's
// external-coordination list; not something this repo or intranet-devops can confirm).
buildPipeline(
    jdk: 'jdk21',
    sonarProjectKey: 'xms',
    envSecret: 'paves/xms/env'
)
