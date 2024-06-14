<!DOCTYPE HTML>
<html class="frm10">
<head>
    <title>${app} - 主题</title>
    <meta http-equiv="Content-Type" content="text/html; charset=UTF-8 "/>
    <link rel="shortcut icon" type="image/x-icon" href="/favicon.ico"/>
    <link rel="stylesheet" href="${css}/main.css"/>
    <script src="${js}/lib.js"></script>
    <script src="${js}/layer/layer.js"></script>
    <style>
        datagrid b{color: #8D8D8D;font-weight: normal}
    </style>
</head>
<body>
<toolbar class="blockquote">
    <left>主题</left>
    <right></right>
</toolbar>
<datagrid class="list">
    <table>
        <thead>
        <tr>
            <td width="200px" class="left">主题</td>
            <td class="left">队列（由订阅时自动产生）</td>
        </tr>
        </thead>
        <tbody id="tbody">
        <#list list as item>
            <tr>
                <td class="left">${item.topic}</td>
                <td class="left break">${item.queueList}</td>
            </tr>
        </#list>
        </tbody>
    </table>
</datagrid>

</body>
</html>