<#import "layout.ftlh" as layout>
<@layout.page title="Authorize application">
    <p><strong>${clientName}</strong> wants to access your account, <strong>${username}</strong>, with these permissions:</p>
    <ul>
        <#list scopes as scope><li><code>${scope}</code></li></#list>
    </ul>
    <form class="actions" method="post" action="${authorizePath}">
        <input type="hidden" name="client_id" value="${clientId}">
        <input type="hidden" name="state" value="${state}">
        <#list scopes as scope><input type="hidden" name="scope" value="${scope}"></#list>
        <button type="submit">Authorize</button>
    </form>
    <#-- Sending no scopes is how a denial is expressed to the authorization endpoint. -->
    <form class="actions" method="post" action="${authorizePath}">
        <input type="hidden" name="client_id" value="${clientId}">
        <input type="hidden" name="state" value="${state}">
        <button type="submit">Deny</button>
    </form>
</@layout.page>
