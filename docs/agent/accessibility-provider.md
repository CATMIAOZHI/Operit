# Operit Ry 无障碍组件

源码在 `accessibility-provider/`，显示名称为 Operit Ry，应用 ID 为
`com.rainy.operitry.provider`。主应用构建会先构建组件，并将签名 APK
作为 `operit-ry-accessibility.apk` 打包；安装入口不再要求输入风险确认句。

组件复用主应用的 AIDL 定义。每次 Binder 调用校验调用包名和签名，
仅允许同签名的个人版、Dev 和 Clone。构建使用与主应用相同的签名配置。
截图通过文件描述符输出，不要求两个应用共享存储路径。

无障碍设置卡提供“通过 ADB 一键启用无障碍”，使用已授权的 Shizuku shell，
保留其他已启用的服务，并检查本组件是否实际生效。也保留系统设置入口。
该功能不代替 Android 的安装确认。

点击和滑动使用 `dispatchGesture`；输入仅操作已核对身份的焦点编辑节点；
窗口身份直接读取当前根节点的包名和窗口 ID，不依赖 XML 导出。
截图要求 Android 11 或以上；受保护的页面可能拒绝截图。

主应用和组件必须一起更新；旧官方 provider 不会自动卸载或修改。
