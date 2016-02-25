# ShipIt to Marketplace for Atlassian Bamboo

![Travis build status](https://travis-ci.org/mibexsoftware/shipit2marketplace.svg?branch=master)

*Ship your plug-ins to the Atlassian Marketplace with one click*

This Bamboo task uploads your plug-ins to the Atlassian Marketplace. Its use case is that you create a release in JIRA
with a new build and the plug-in collects the necessary information like the release version, the 
name and the summary from the associated JIRA version. This means that you don't have to supply the information for
a new Marketplace manually, but instead the plug-in is able to do this.

All you need to do is to add the provided Bamboo task to your build and configure it as can be seen in this screenshot:

![Screenshot global pull request comment plugin](doc/task-config.png)

Then, create a release with a new build in JIRA:

![Screenshot global pull request comment plugin](doc/release-from-jira.png)

And a new release is created in the Atlassian Marketplace:

![Screenshot global pull request comment plugin](doc/marketplace-version.png)

Note that the plug-in will NOT create a Marketplace release if the build is not triggered from JIRA. This prevents
releases from ordinary Bamboo plan builds.

## Installation

The plug-in will probably once be available in the Atlassian Marketplace. Until then, you can download it from our 
[Github releases page](https://github.com/mibexsoftware/shipit2marketplace/releases/latest).


## Configuration

Beside the options in the Bamboo task configuration there is also a possibility to override the build number as a build
variable. This is useful if you do not want the plug-in to deduce the build number from the plug-ins version number (e.g.,
a plug-in version 1.2.3 results in the deduced build number 100200300). To override this behaviour, just pass the build
variable "shipit2mpac.buildnr" from the JIRA release dialog:

![Screenshot global pull request comment plugin](doc/build-variable.png)
