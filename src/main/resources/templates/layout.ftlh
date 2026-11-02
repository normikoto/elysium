<#macro page title>
<!doctype html>
<html lang="en">
<head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>${title} - Elysium</title>
    <style>
        :root { color-scheme: light dark; }
        body { font-family: system-ui, sans-serif; line-height: 1.5; margin: 0 auto; max-width: 26rem; padding: 2rem 1rem; }
        h1 { font-size: 1.5rem; }
        label { display: block; margin-top: 1rem; }
        input[type=text], input[type=password] { box-sizing: border-box; font: inherit; padding: 0.5rem; width: 100%; }
        button { font: inherit; margin-top: 1rem; padding: 0.5rem 1rem; }
        code { display: block; overflow-wrap: anywhere; padding: 0.5rem 0; user-select: all; }
        form.actions { display: inline-block; margin-right: 0.5rem; }
        .notice { font-weight: 600; }
    </style>
</head>
<body>
<h1>${title}</h1>
<#nested>
</body>
</html>
</#macro>
