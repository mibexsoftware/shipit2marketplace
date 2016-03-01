# ShipIt to Marketplace for Atlassian Bamboo

![Travis build status](https://travis-ci.org/mibexsoftware/shipit2marketplace.svg?branch=master)

*Ship your plug-ins to the Atlassian Marketplace with one click*

This Bamboo task uploads your plug-ins to the Atlassian Marketplace. Its intended use case is that you create a release 
in JIRA with a new build and the plug-in collects the necessary information like the release version, the 
name and the summary from the associated JIRA version. This means that you don't have to supply all the information for
a new Marketplace version of your plug-in manually, but instead this Bamboo task is able to do that. It also creates 
release notes based on the JIRA issues associated with your JIRA version.

All you need to do is to add this Bamboo task to your build and configure it as can be seen in this screenshot:

![Screenshot Bamboo task configuration](doc/task-config.png)

Then, create a release with a new build in JIRA:

![Screenshot of how to trigger a release in JIRA](doc/release-from-jira.png)

And a new release is created in the Atlassian Marketplace:

![New Marketplace version](doc/marketplace-version.png)

Note that the plug-in will NOT ship your plug-in to the Marketplace if the build was not triggered from JIRA. 
This prevents releases from "ordinary" Bamboo plan builds. The plug-in also supports Bamboo deployment projects.

## Installation

The plug-in will probably once be available in the Atlassian Marketplace. Until then, you can download it from our 
[Github releases page](https://github.com/mibexsoftware/shipit2marketplace/releases/latest).


## Configuration

You can override the build number, the release summary and the release notes with Bamboo plan variables. Overriding
the build number is useful if you do not want the plug-in to deduce the build number from the plug-in's version number 
(e. g., a plug-in's version 1.2.3 results in the deduced build number 100200300). To override this behaviour, just pass 
the build variable "bamboo.shipit2mpac.buildnr" from the JIRA release dialog:

![Screenshot Bamboo variable to override the build number](doc/build-variable.png)

Overriding the release summary and release notes can be useful if you don't want to take the summary from the JIRA version
description and the deduced release notes from the resolved JIRA issues. Use the following two plan variables to achieve this:

* bamboo.shipit2mpac.release.notes
* bamboo.shipit2mpac.release.summary

The Bamboo task requires that you configure an artifact to deploy as your new plug-in version. 
See [this Atlassian page](https://confluence.atlassian.com/display/BAMBOO058/Sharing+artifacts) on how to achieve this.
