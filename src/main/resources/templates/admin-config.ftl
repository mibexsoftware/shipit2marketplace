<html>
<head>
    <title>[@ww.text name='shipit.admin.title' /]</title>
    <meta name="decorator" content="adminpage">
</head>
<body>
<h1>[@ww.text name='shipit.admin.title' /]</h1>

[@ww.form action="updateShip2MpacConfiguration.action" method="post" submitLabelKey='shipit.admin.save']
    <h2>[@ww.text name='shipit.admin.credentials.section' /]</h2>
    <p>[@ww.text name='shipit.admin.credentials.section.details' /]</p>
    <br>

    [@ww.textfield name='vendorName' labelKey='shipit.admin.vendor.login' descriptionKey='shipit.admin.vendor.login.description' required='true' /]
    [@ww.password name='vendorApiToken' labelKey='shipit.admin.vendor.apitoken' descriptionKey='shipit.admin.vendor.apitoken.description' required='true' showPassword='true' /]

[/@ww.form]
</body>
</html>