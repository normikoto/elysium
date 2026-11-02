<#import "layout.ftlh" as layout>
<@layout.page title="Create an account">
    <#if error??><p class="notice" role="alert">${error}</p></#if>
    <form method="post" action="/signup">
        <input type="hidden" name="${csrfParameter}" value="${csrfToken}">
        <label for="username">Username</label>
        <input type="text" id="username" name="username" value="${username}" autocomplete="username" required autofocus>
        <label for="password">Password</label>
        <input type="password" id="password" name="password" autocomplete="new-password" minlength="8" maxlength="128"
               required>
        <label for="confirmPassword">Confirm password</label>
        <input type="password" id="confirmPassword" name="confirmPassword" autocomplete="new-password" required>
        <button type="submit">Create account</button>
    </form>
    <p><a href="/login">Log in instead</a></p>
</@layout.page>
