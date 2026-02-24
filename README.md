MC2CMD
轻量级 Minecraft CMD 执行模组 | Lightweight In-Game CMD Execution Mod
版本 Version: 1.10.0适用版本 Minecraft: 1.20.1加载器 Loader: Fabric模组体积 Size: 0.01MB多语言支持 Languages: 10 种主流语言 | 10 Mainstream Languages
简介 | Introduction
MC2CMD 是一款极致轻量的 Fabric 模组，让你无需退出《我的世界》，即可在游戏内直接执行 Windows CMD 命令，支持普通权限与管理员权限双模式。模组体积不足 0.01MB，加载零延迟，与 100 + 模组共存无冲突，完美适配大型整合包。
MC2CMD is an ultra-lightweight Fabric mod that allows you to execute Windows CMD commands directly in Minecraft without exiting the game, supporting both normal and administrator permission modes. With a size of less than 0.01MB, it loads instantly, has no conflicts with 100+ mods, and is fully compatible with large modpacks.
核心功能 | Core Features
双指令体系：提供普通权限与管理员权限两种执行方式，满足不同操作需求。
10 国语言适配：游戏内提示语随系统语言自动切换，小众语言默认英文兜底。
极致轻量：核心代码精简，无冗余资源，不占用额外内存与 CPU，不影响游戏帧率。
安全转义：自动处理特殊字符（"、&），避免命令执行异常。
高兼容性：完美适配 Fabric 生态，与各类模组、整合包无冲突。
支持语言 | Supported Languages
表格
语言 Language	代码 Code	语言 Language	代码 Code
简体中文	zh_cn	德语	de_de
英语（兜底）	en_us	法语	fr_fr
日语	ja_jp	西班牙语	es_es
韩语	ko_kr	葡萄牙语（巴西）	pt_br
俄语	ru_ru	意大利语	it_it
指令说明 | Command Usage
前置要求 | Prerequisite
需拥有游戏4 级最高权限（服务器端需为管理员，单人模式需开启作弊）。
1. 普通权限执行 | Normal Permission Execution
minecraft
/cmd <你的CMD命令>
示例 Example：/cmd dir（查看当前目录文件）、/cmd notepad（打开记事本）
2. 管理员权限执行 | Administrator Permission Execution
minecraft
/cmdadmin <你的CMD命令>
示例 Example：/cmdadmin net stop（停止本地服务）、/cmdadmin chkdsk（磁盘检查）
安装方法 | Installation
确保你的游戏已安装 Fabric Loader ≥0.15.0 和 Fabric API；
将 mc2cmd-1.10.0.jar 放入游戏目录下的 mods 文件夹；
启动游戏，模组自动加载生效。
注意事项 | Notes
仅支持 Windows 系统，Linux/Mac 系统暂不兼容；
管理员权限指令会弹出系统 UAC 授权窗口，需手动确认；
请谨慎执行高危 CMD 命令（如格式化、删除系统文件），避免造成数据丢失；
模组仅用于个人学习与自用，服务器端使用请提前告知玩家并做好安全防护。
开发者 | Developer
杨晨硕 (YangChenshuo)
许可证 | License
MIT License
3. 
