<fieldset>
    [@ww.select name="artifactToDeployKey" labelKey="shipit.task.artifact" list=allArtifactsToDeploy
                listKey="value" listValue="displayName" groupBy="group" required='true' /]
    [@ww.checkbox labelKey="shipit.task.publicVersion" name="publicVersion"/]
    [@ww.checkbox labelKey="shipit.task.deduceBuildNrFromPluginVersion" name="deduceBuildNrFromPluginVersion"/]
    [@ww.textfield labelKey='shipit.task.user' name='bambooUserId' template='userPicker' multiSelect=false
                   placeholderKey='shipit.task.user.placeholder' /]
</fieldset>