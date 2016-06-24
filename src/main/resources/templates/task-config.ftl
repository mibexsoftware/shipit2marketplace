<fieldset>
    [@ww.select name="artifactToDeployKey" labelKey="shipit.task.artifact" list=allArtifactsToDeploy
                listKey="value" listValue="displayName" groupBy="group" required='true' /]
                [@ww.checkbox labelKey="shipit.task.release.panel.mode" name="jiraReleasePanelDeploymentOnly" toggle='true' /]
    [@ui.bambooSection dependsOn='jiraReleasePanelDeploymentOnly' showOn='false']
        [@ww.select labelKey='shipit.task.projectKey' descriptionKey='shipit.task.projectKey.description'
        name='jiraProjectKey' id='jiraProjectKey' listKey='key' listValue='name'
        toggle='true' list=jiraProjects required='true'/]
    [/@ui.bambooSection]
    [@ww.checkbox labelKey="shipit.task.publicVersion" name="publicVersion" /]
    [@ww.checkbox labelKey="shipit.task.deduceBuildNrFromPluginVersion" name="deduceBuildNrFromPluginVersion" /]
    [@ww.checkbox labelKey="shipit.task.runOnBranchBuilds" name="runOnBranchBuilds" /]
    [@ww.textfield labelKey="shipit.task.user" name="bambooUserId" template="userPicker" multiSelect=false
                   placeholderKey="shipit.task.user.placeholder" /]
</fieldset>