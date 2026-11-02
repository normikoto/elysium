<#import "layout.ftlh" as layout>
<#if code??>
    <@layout.page title="Authorization code">
        <p>Copy this code and paste it into the application:</p>
        <code>${code}</code>
    </@layout.page>
<#else>
    <@layout.page title="Authorization failed">
        <p>The application wasn't authorized: <code>${error}</code></p>
    </@layout.page>
</#if>
