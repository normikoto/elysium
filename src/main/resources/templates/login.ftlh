<#import "layout.ftlh" as layout>
<@layout.page title="Log in">
    <#if failed><p class="notice" role="alert">Incorrect username or password.</p></#if>
    <#if busy><p class="notice" role="alert">The server is busy. Try again in a moment.</p></#if>
    <#if loggedOut><p class="notice">You've logged out.</p></#if>
    <#if registered><p class="notice">Your account is ready. Log in to continue.</p></#if>
    <form method="post" action="/login">
        <input type="hidden" name="${csrfParameter}" value="${csrfToken}">
        <label for="username">Username</label>
        <input type="text" id="username" name="username" autocomplete="username" required autofocus>
        <label for="password">Password</label>
        <input type="password" id="password" name="password" autocomplete="current-password" required>
        <button type="submit">Log in</button>
    </form>
    <#if registrationEnabled><p><a href="/signup">Create an account</a></p></#if>
</@layout.page>
