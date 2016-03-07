package ch.mibex.bamboo.shipit


object Constants {
  val ResultLinkPluginBinaryUrl = "shipItPluginBinaryUrl"
  val ResultLinkPluginVersion = "shipItPluginVersion"
  val BambooBuildNrVariableKey = "bamboo.shipit2mpac.buildnr"
  val BambooReleaseNotesVariableKey = "bamboo.shipit2mpac.release.notes"
  val BambooReleaseSummaryVariableKey = "bamboo.shipit2mpac.release.summary"
  val BambooPluginBaseVersionVariableKey = "bamboo.shipit2mpac.plugin.base.version"
  val JiraReleaseTriggerReasonKey = "com.atlassian.bamboo.plugin.jira:jiraReleaseTriggerReason"
  val PluginTaskKey = "shipit2marketplace.task"
  val MaxReleaseSummaryLength = 80
  val MaxReleaseNotesLength = 1000
}
