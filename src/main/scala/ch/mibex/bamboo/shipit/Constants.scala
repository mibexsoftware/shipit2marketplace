package ch.mibex.bamboo.shipit


object Constants {
  val ResultLinkPluginBinaryUrl = "shipItPluginBinaryUrl"
  val ResultLinkPluginVersion = "shipItPluginVersion"

  val BambooJiraVersionVariableKey = "shipit2mpac.jiraversion"
  val BambooBuildNrVariableKey = "shipit2mpac.buildnr"
  val BambooReleaseNotesVariableKey = "shipit2mpac.release.notes"
  val BambooReleaseSummaryVariableKey = "shipit2mpac.release.summary"
  val BambooPluginBaseVersionVariableKey = "shipit2mpac.plugin.base.version"

  // we cannot use the constant from the Bamboo JIRA plug-in because of BAM-17061
  val JiraReleaseTriggerReasonKey = "com.atlassian.bamboo.plugin.jira:jiraReleaseTriggerReason"
  val PluginTaskKey = "shipit2marketplace.task"
  val MaxReleaseSummaryLength = 80
  val MaxReleaseNotesLength = 1000
}
