package com.eratw.port.android

/**
 * 首启教程（复古像素指南）与关于页「重新查阅」入口的三语文案表（ZH / EN / JA）。
 *
 * ## 为什么独立成表
 * 照 [PlayerStatsCopy] 的同款理由：[SettingsStrings] 的构造参数已贴着 JVM 方法签名
 * 255 个参数槽的上限，再加字段会在运行期抛 ClassFormatError。新增文案一律另起小表，
 * 语言只经 [SettingsLanguage] 单路取，形状闸照 `TutorialCopyTest`（改表必改闸）。
 *
 * ## 口径
 * 玩家面措辞为面向玩家的说明口吻；功能落点（分区名、卡名、投放目录、生效时机）逐字对齐
 * 现有权威文案：分区名取 [SettingsStrings] 的 zone*Tab，卡名取 [ResourcePackCopy]
 * 与 [ModImportCopy]，关于页的按钮名取 [SettingsStrings.checkUpdateButton] /
 * [diagCopyButton] / [diagShareButton]。
 *
 * ## 版式契约
 * 正文按 `'\n'` 分块，**一行一块**，块首前缀表达结构：`# 小标题`、`- 列表项`、
 * `1. 步骤`、`---` 分隔线，其余当段落。行内三种标记 `**强调**`（米白）、
 * `*高亮*`（游戏高亮黄）、`` `路径` ``（水色，可照抄的目录 / 文件名）**必须成对**。
 * 三语的块数与块类型逐条对齐（形状闸钉行数），加文案要三档一起加；块首前缀逐行一致，
 * 译文只翻前缀后面的正文。
 *
 * ## 页面
 * 七页：欢迎 / 认识设置菜单 / 存档 / 素材 / 口上包 / 游玩建议 / 资源与反馈。
 * 页序在 `TutorialMarkup.kt` 的 `tutorialPages`，本表只提供字段。
 *
 * ## 点阵字覆盖
 * 正文用 Ark Pixel 12px 点阵字。字面已避开它缺的字形（可换词处一律换掉，比如
 * 「大致」「忽然」「照样」「移到」「复现方式」），仍留在正文里的缺字只有四枚专名：
 * 中文「立即检查更新」的「即」、「原版引擎」的「擎」，日文「幻想郷」「教養」。
 * 这四枚由平台字体兜底（与游戏渲染层的字体回退同一套兜底逻辑），缺字清单的出处是
 * `app/src/main/res/font/ark_pixel.ttf` 的 cmap，预览器 `coverage` 模式可复跑。
 *
 * @property lang 这一组对应的设置页语言
 */
data class TutorialCopy(
    val lang: SettingsLanguage,
    // ---- 书脊与七页 ----
    val bookTitle: String,
    val welcomeTitle: String,
    val welcomeBody: String,
    val setupTitle: String,
    val setupBody: String,
    val savesTitle: String,
    val savesBody: String,
    val mediaTitle: String,
    val mediaBody: String,
    val koujoTitle: String,
    val koujoBody: String,
    val tipsTitle: String,
    val tipsBody: String,
    val resourcesTitle: String,
    val resourcesBody: String,
    // ---- 翻页与收场 ----
    val prevButton: String,
    val nextButton: String,
    val startButton: String,
    /** 关闭钮的无障碍描述（不上屏，读屏器用）。 */
    val closeAction: String,
    // ---- 关于页的重新查阅入口 ----
    val revisitTitle: String,
    val revisitHint: String,
    val revisitButton: String,
) {
    companion object {
        private val ZH = TutorialCopy(
            lang = SettingsLanguage.ZH,
            bookTitle = "eraTW 移植版 · 新手指南",
            welcomeTitle = "欢迎来到 eraTW！",
            welcomeBody = "欢迎使用 eraTW 安卓移植版！\n" +
                "如果你是第一次接触 eraTW，可以把它理解成一款规模相当庞大的东方 Project 二次同人文字冒险游戏。\n" +
                "这个版本是将原本运行在电脑上的 eraTW 挪到了安卓手机上。我们在手机上重写了原版 Emuera 引擎，尽可能保留原版的游戏体验，同时无需繁琐的模拟器。\n" +
                "除了让游戏能在手机上运行，我们也补上了一些移动端比较需要的功能：**存档导入与导出、背景图片与音乐导入、第三方口上包安装**，以及版本检查和崩溃诊断等。\n" +
                "这些功能都可以在游戏右上角的「设置」中找到。\n" +
                "接下来，我们会依次介绍存档、背景素材和口上包的使用方法，再分享一些游玩小建议和实用资源。\n" +
                "阅读时可以留意一下：*黄色文字* 是需要特别注意的关键词，`青色文字` 则是可以直接照着寻找的目录名或文件名。\n" +
                "这份指南只会在首次安装后自动弹出一次。如果之后想重新查看，可以随时前往「设置 → 关于」找到它。\n" +
                "如果你已经迫不及待想进游戏，也可以直接点右上角的关闭按钮，教程不会跑掉。",
            setupTitle = "认识一下设置菜单",
            setupBody = "游戏画面右上角就是「设置」入口。打开之后，可以看到四个分区：\n" +
                "- **移植版功能**：存档导入、导出等安卓移植版专属功能。\n" +
                "- **原版引擎**：原版游戏引擎的相关设置。\n" +
                "- **素材与补丁**：导入背景图片、BGM 和第三方口上包。\n" +
                "- **关于与反馈**：查看版本信息、检查更新，以及获取反馈渠道。\n" +
                "后面会经常用到这些入口，先记住大致的位置就好，不用特意背下来。",
            savesTitle = "存档导入与导出",
            savesBody = "好不容易玩出来的进度，可千万别弄丢了。\n" +
                "移植版支持与电脑版互通存档，两边使用的是相同的存档格式。因此，无论是想把电脑上的进度移到手机，还是想把手机里的存档带回电脑，都可以通过这里完成。\n" +
                "# 在哪里操作？\n" +
                "打开「设置 → 移植版功能 → 存档」，存档管理就在这个分区的最上方。\n" +
                "# 导入存档\n" +
                "1. 点击「选择存档文件」。\n" +
                "2. 在系统文件选择器中，找到需要导入的存档。\n" +
                "3. 选中对应的 `.sav` 文件就好，也可以一次选择多个文件。\n" +
                "电脑版的各个存档位和全局存档 `global.sav` 都可以导入。应用会自动识别文件对应的存档位置，不需要自己手动指定。\n" +
                "如果目标位置已经有存档，系统会先询问是否覆盖。确认之前不会修改原有存档，所以选错文件也不用慌，重新选择就好。\n" +
                "# 导出存档\n" +
                "点击「一键导出存档」，应用会把所有存档位和全局存档一起打包成一个 ZIP 文件，保存到你指定的位置。\n" +
                "以后换手机、重装游戏，或者想把自己的进度分享给朋友，都可以用这个 ZIP 文件。\n" +
                "# 几个小提醒\n" +
                "- 换手机或重装游戏之前，记得先导出一次存档。\n" +
                "- 导入别人分享的存档之前，也建议先备份自己的进度。\n" +
                "- 全局存档和普通存档位是分开保存的，不过不用担心，一键导出会把它们全部打包进去。\n" +
                "- 导出的 ZIP 中已经按存档文件分类整理，换机时解压后，把需要的 `.sav` 文件一起选中导入就好。\n" +
                "养成定期备份的习惯吧。毕竟，比起重新肝进度，提前花几秒钟备份还是划算得多。",
            mediaTitle = "背景图片与背景音乐",
            mediaBody = "玩久了原版画面，想换点新鲜感？或者想给游戏配上自己喜欢的音乐？\n" +
                "移植版支持自行导入背景图片和 BGM，不需要手动修改游戏目录，也不用自己折腾打包。\n" +
                "相关功能都在「设置 → 素材与补丁」中。\n" +
                "# 导入背景图片\n" +
                "找到「导入背景图片」卡片，可以选择：\n" +
                "- 单张或多张图片文件。\n" +
                "- 包含图片的整个文件夹。\n" +
                "- 官方背景图片整合包（`.zip` / `.7z`）。\n" +
                "导入的图片会放到 `resources/背景图/` 目录下。\n" +
                "如果使用官方整合包，建议直接导入整个压缩包，不要单独拆出图片文件。因为整合包里还包含 `BGI.csv` 索引文件，缺少它可能会影响图片的识别。\n" +
                "如果没有索引文件，应用也会尝试根据文件名自动生成索引。不过这里有个小要求：**图片文件名需要符合游戏使用的命名规则**，也就是「地点_季节_时间」的格式。名字对不上，游戏就可能找不到对应的图片。\n" +
                "# 导入背景音乐\n" +
                "找到「导入 BGM」卡片，选择 `.mp3` 文件或整个文件夹就行。\n" +
                "导入的曲目会按照地点放入 `sound/游戏bgm/` 下对应的目录。如果有特殊需要，也可以手动指定存放位置。\n" +
                "另外，文件名不必提前全部改成数字。对于文件名不是 0-9 的曲目，应用会自动分配编号，省去不少整理工作的麻烦。\n" +
                "# 导入后需要重启吗？\n" +
                "这里有个区别：\n" +
                "- **背景图片**：需要重启游戏后才能生效。\n" +
                "- **背景音乐**：不需要重启，在下一次播放时就会生效。\n" +
                "每次导入完成后，卡片上都会显示导入成功的文件数量，以及被跳过的文件。遇到素材没有正常显示的情况，可以先看看这里的导入结果。",
            koujoTitle = "安装第三方口上包",
            koujoBody = "如果你在 eraTW 社区待过一段时间，也许已经听说过「口上」了。\n" +
                "简单来说，口上就是由玩家创作的角色对话脚本。不同作者有不同的写作风格，有的补充原版没有的对话，有的则会为角色带来一整套全新的互动体验。\n" +
                "安装口上包后，对应角色的对话就可能变成作者创作的内容。这也是 eraTW 社区非常重要的一部分。\n" +
                "# 怎么安装？\n" +
                "打开「设置 → 素材与补丁 → 补丁（MOD）导入」，点击「打开补丁导入」。\n" +
                "你可以选择：\n" +
                "- `.ERB/.ERH` 口上源文件。\n" +
                "- 包含口上文件的 ZIP 整合包。\n" +
                "- 一次选择多个文件。\n" +
                "导入的源文件会保存到 `mods` 目录，并自动编译成游戏补丁。\n" +
                "**注意：安装完成后，需要重启游戏才能生效。**\n" +
                "# 如果口上包编译失败怎么办？\n" +
                "不用太担心。\n" +
                "有些第三方口上包可能使用了特殊语法，或者依赖原版游戏中的某些函数，未必能直接通过移植版的编译。\n" +
                "遇到这种情况，应用会生成错误报告，告诉你具体是哪个文件出了问题，以及相应的错误原因。\n" +
                "其他能够正常编译的内容照样能运行，不会因为某一个文件出错，就导致整个游戏无法启动。\n" +
                "# 一些安装建议\n" +
                "- 第一次安装口上包时，建议先从小型整合包开始。\n" +
                "- 尽量一次安装一套，进游戏确认正常后，再安装下一套。出了问题也更容易找到原因。\n" +
                "- 口上包主要修改角色台词，不会直接修改存档数据，因此可以根据自己的喜好安装或卸载。\n" +
                "还有一点需要提醒：大型口上包首次安装时可能需要编译不少文件，启动等待时间会比平时长一些。看到编译进度时，耐心等它完成就好。",
            tipsTitle = "几个游玩小建议",
            tipsBody = "如果你是第一次玩 eraTW，面对庞大的游戏内容可能会有些不知所措。这里整理了几条比较实用的建议，或许能帮你少走一些弯路。\n" +
                "1. **留意时间安排**：游戏中的大多数行动都会推进时间，不同时间段也会发生不同的事情。不过，主人公拥有时间停止能力，善用这项能力，可以在不消耗游戏时间的情况下处理一些事务。\n" +
                "2. **前期先攒点家底**：打扫、打工都是不错的起步方式。积累一些资金和人脉，之后购买道具、维持日常开销都会轻松不少。\n" +
                "3. **别忘了提升技能**：战斗、音乐、料理、教养等技能都值得花时间培养。随着技能提升，你会逐渐解锁更多互动和事件，部分工作的成功率也会提高。\n" +
                "4. **多和角色打交道**：聊天、赠送礼物，都是提升角色好感的方式。当好感度和其他条件达到要求后，还会逐步解锁新的关系发展、口上和特殊事件。\n" +
                "5. **记得经常存档**：这一条真的很重要。游戏内容比较庞大，探索过程中难免会遇到意外。养成定期存档的习惯，导入外部存档前也别忘了备份自己的进度。\n" +
                "6. **别总待在一个地方**：不同地点、不同时间段，都可能遇到不同的角色和事件。没事的时候多在地图上转转，说不定就能发现一些意料之外的内容。\n" +
                "7. **遇到问题先别急着清档**：如果游戏出现异常、卡住或者画面显示不正常，可以先尝试重启应用。如果问题还在，可以前往「设置 → 关于」，使用「复制诊断」或「导出并分享诊断」功能，将诊断信息提供给开发者。比起只说一句「游戏坏了」，带上诊断信息会更容易找到问题所在。",
            resourcesTitle = "更多资源与反馈渠道",
            resourcesBody = "一个人玩也没问题，不过 eraTW 的乐趣，很大一部分也来自玩家社区。\n" +
                "如果想寻找更多口上、整合包、游戏资源，或者看看其他玩家的攻略，可以去逛逛百度贴吧的 **「eratw吧」**。那里有不少玩家分享的内容，也欢迎大家一起交流。\n" +
                "# 遇到问题，或者想了解最新版本？\n" +
                "打开「设置 → 关于」，你可以找到：\n" +
                "- 当前游戏版本信息。\n" +
                "- 项目仓库与相关地址。\n" +
                "- Bug 反馈群群号（可以直接复制）。\n" +
                "- 诊断信息导出功能。\n" +
                "- 版本更新检查。\n" +
                "如果遇到闪退、黑屏、卡死或画面异常，建议先使用「复制诊断」或「导出并分享诊断」，再到反馈群里描述问题。\n" +
                "诊断信息包含版本、设备型号和相关运行现场，能帮助开发者更快定位问题。反馈时如果还能附上复现方式，就更好了。\n" +
                "另外，关于页还提供「立即检查更新」功能。想体验新版本，可以随时手动检查；如果不希望应用自动检查更新，也可以关闭「自动检查更新」。\n" +
                "好了，介绍就到这里。\n" +
                "希望这个移植版能让你在手机上也能享受到 eraTW 的乐趣。\n" +
                "幻想乡的故事还有很多，慢慢探索就好。\n" +
                "**祝你玩得开心！**",
            prevButton = "上一页",
            nextButton = "下一页",
            startButton = "开始游戏",
            closeAction = "关闭指南",
            revisitTitle = "新手指南",
            revisitHint = "首次启动时展示的移植版功能指南与游玩建议，可在此重新查阅。",
            revisitButton = "重新查阅",
        )

        private val EN = TutorialCopy(
            lang = SettingsLanguage.EN,
            bookTitle = "eraTW Android Port: Beginner's Guide",
            welcomeTitle = "Welcome to eraTW!",
            welcomeBody = "Welcome to the Android port of eraTW!\n" +
                "If this is your first time with eraTW, think of it as a fairly large Touhou Project fan-made text adventure.\n" +
                "This version brings eraTW, which originally ran on PC, over to Android phones. We rewrote the original Emuera engine for mobile, keeping the original experience as intact as possible while doing away with clunky emulators.\n" +
                "Beyond getting the game to run on a phone, we also added the features mobile players tend to need: **save import and export, background image and music import, and third-party koujo pack installation**, plus version checks and crash diagnostics.\n" +
                "All of these can be found under Settings in the top-right corner of the game.\n" +
                "Next, we will walk through saves, background assets, and koujo packs in turn, then share a few playing tips and some useful resources.\n" +
                "As you read, keep an eye out: *yellow text* marks key terms worth extra attention, while `cyan text` points to directory or file names you can look up directly.\n" +
                "This guide only pops up automatically once, after the first installation. If you would like to read it again later, you can always find it under Settings > About.\n" +
                "If you are eager to jump into the game, just tap the close button in the top-right corner. The guide is not going anywhere.",
            setupTitle = "Getting to Know the Settings Menu",
            setupBody = "The Settings entry sits in the top-right corner of the game screen. Open it and you will see four sections:\n" +
                "- **Android port features**: Port-exclusive features such as save import and export.\n" +
                "- **Original engine**: Settings for the original game engine.\n" +
                "- **Assets and patches**: Import background images, BGM, and third-party koujo packs.\n" +
                "- **About and feedback**: View version information, check for updates, and find feedback channels.\n" +
                "You will be using these entries often later on, so just remember roughly where they are; there is no need to memorize them.",
            savesTitle = "Save Import and Export",
            savesBody = "Do not lose the progress you worked so hard to build up.\n" +
                "The port shares save compatibility with the PC version, since both use the same save format. So whether you want to carry your PC progress over to your phone or bring your phone saves back to PC, this is where to do it.\n" +
                "# Where is it?\n" +
                "Open Settings > Android port features > Saves; save management sits at the very top of this section.\n" +
                "# Importing saves\n" +
                "1. Tap \"Select save file\".\n" +
                "2. In the system file picker, locate the save you want to import.\n" +
                "3. Just select the matching `.sav` files; you can pick several at once.\n" +
                "Every save slot from the PC version, as well as the global save `global.sav`, can be imported. The app recognizes the correct slot for each file automatically, so you do not need to specify it yourself.\n" +
                "If the target slot already holds a save, the app will ask before overwriting. Nothing is changed until you confirm, so there is no harm in picking the wrong file; just choose again.\n" +
                "# Exporting saves\n" +
                "Tap \"Export all saves\" and the app bundles every save slot plus the global save into a single ZIP file, saved wherever you choose.\n" +
                "Whether you are switching phones, reinstalling the game, or sharing your progress with a friend, this ZIP file has you covered.\n" +
                "# A few reminders\n" +
                "- Before switching phones or reinstalling the game, remember to export your saves once.\n" +
                "- Before importing saves that someone else shared, it is also a good idea to back up your own progress first.\n" +
                "- The global save and regular save slots are stored separately, but do not worry: a one-tap export packs them all together.\n" +
                "- The exported ZIP is already sorted by save file. When changing phones, unzip it and select the `.sav` files you need to import them together.\n" +
                "Get into the habit of backing up regularly. After all, a few seconds of backup beats grinding all that progress again.",
            mediaTitle = "Background Images and Background Music",
            mediaBody = "Tired of the original visuals after hours of play? Or want to set the game to music you actually like?\n" +
                "The port lets you import your own background images and BGM, with no manual edits to the game directory and no repackaging hassle.\n" +
                "These features all live under Settings > Assets and patches.\n" +
                "# Importing background images\n" +
                "Find the \"Import background images\" card, where you can choose:\n" +
                "- One or more image files.\n" +
                "- An entire folder containing images.\n" +
                "- The official background image pack (`.zip` / `.7z`).\n" +
                "Imported images go into the `resources/背景图/` directory.\n" +
                "If you are using the official pack, import the whole archive rather than extracting individual image files. The pack also contains a `BGI.csv` index file, and leaving it out may affect image recognition.\n" +
                "If there is no index file, the app will try to generate one from the file names. There is one small requirement, though: **image file names must follow the naming rule the game uses**, that is, the \"地点_季节_时间\" format. If the names do not match, the game may not find the corresponding images.\n" +
                "# Importing background music\n" +
                "Find the \"Import BGM\" card and select `.mp3` files or an entire folder.\n" +
                "Imported tracks are placed into the matching location directory under `sound/游戏bgm/`. If you have special needs, you can also specify the destination manually.\n" +
                "Also, you do not have to rename every file to numbers beforehand. For tracks whose file names are not 0-9, the app assigns numbers automatically, saving you a lot of sorting work.\n" +
                "# Do I need to restart after importing?\n" +
                "There is a difference here:\n" +
                "- **Background images**: take effect only after restarting the game.\n" +
                "- **Background music**: no restart needed; it takes effect the next time it plays.\n" +
                "After each import, the card shows how many files were imported successfully and which were skipped. If some assets do not display properly, check the import result here first.",
            koujoTitle = "Installing Third-Party Koujo Packs",
            koujoBody = "If you have spent some time around the eraTW community, you have probably heard of koujo (dialogue packs).\n" +
                "Put simply, koujo are character dialogue scripts written by players. Different authors have different styles: some add lines the original game lacks, while others give a character an entirely new set of interactions.\n" +
                "Once a koujo pack is installed, that character's dialogue may be replaced with the author's writing. This is also a very important part of the eraTW community.\n" +
                "# How do I install one?\n" +
                "Open Settings > Assets and patches > Patch (MOD) import, then tap \"Open patch import\".\n" +
                "You can choose:\n" +
                "- Koujo source files (`.ERB/.ERH`).\n" +
                "- A ZIP pack containing koujo files.\n" +
                "- Multiple files at once.\n" +
                "Imported source files are saved to the `mods` directory and compiled into a game patch automatically.\n" +
                "**Note: after installation, you must restart the game for it to take effect.**\n" +
                "# What if a koujo pack fails to compile?\n" +
                "Do not worry too much.\n" +
                "Some third-party koujo packs may use unusual syntax or rely on functions from the original game, so they will not necessarily compile cleanly with the port.\n" +
                "When that happens, the app generates an error report telling you which file went wrong and why.\n" +
                "Everything that does compile still runs fine. One broken file will not stop the whole game from starting.\n" +
                "# A few installation tips\n" +
                "- For your first koujo pack, start small.\n" +
                "- Install one pack at a time, and confirm it works in-game before adding the next. That also makes problems easier to trace.\n" +
                "- Koujo packs mainly change character dialogue and do not modify save data directly, so you can install or remove them as you like.\n" +
                "One more thing to keep in mind: a large koujo pack may need to compile quite a few files on first install, so startup will take longer than usual. When you see the compile progress, just wait for it to finish.",
            tipsTitle = "A Few Playing Tips",
            tipsBody = "If this is your first time playing eraTW, the sheer amount of content can be a little overwhelming. Here are a few practical tips that may help you avoid some detours.\n" +
                "1. **Watch the clock**: most actions in the game advance time, and different time slots bring different events. The protagonist, however, has the ability to stop time; used well, it lets you handle some business without spending in-game time.\n" +
                "2. **Build up your resources early**: cleaning and part-time work are both good ways to start. Once you have some money and connections, buying items and covering daily expenses becomes much easier.\n" +
                "3. **Do not neglect your skills**: combat, music, cooking, and education are all worth training. As your skills improve, you will gradually unlock more interactions and events, and some jobs will succeed more often.\n" +
                "4. **Spend time with the characters**: chatting and giving gifts are both ways to raise a character's affection. Once affection and other conditions are met, new relationship developments, koujo, and special events gradually unlock.\n" +
                "5. **Save often**: this one really matters. The game is large, and exploration is bound to turn up surprises. Get into the habit of saving regularly, and remember to back up your own progress before importing saves from elsewhere.\n" +
                "6. **Do not stay in one place**: different locations and different times of day can bring different characters and events. Wander around the map when you have nothing else to do; you may stumble onto something unexpected.\n" +
                "7. **Do not rush to wipe your save when something goes wrong**: if the game suddenly misbehaves, freezes, or displays incorrectly, try restarting the app first. If the problem persists, go to Settings > About and use \"Copy diagnostics\" or \"Export and share diagnostics\" to hand the information to the developer. A report with diagnostics makes the problem far easier to pin down than a bare \"the game is broken\".",
            resourcesTitle = "More Resources and Feedback Channels",
            resourcesBody = "Playing on your own is perfectly fine, but a big part of the fun in eraTW comes from the player community.\n" +
                "If you are looking for more koujo, packs, and game resources, or want to read other players' guides, drop by Baidu Tieba's **\"eratw吧\"** forum. Plenty of players share their work there, and everyone is welcome to join the conversation.\n" +
                "# Ran into a problem, or want the latest version?\n" +
                "Open Settings > About, and you will find:\n" +
                "- Current game version information.\n" +
                "- The project repository and related links.\n" +
                "- The Bug report group number (you can copy it directly).\n" +
                "- The diagnostics export feature.\n" +
                "- Version update checks.\n" +
                "If you hit a crash, black screen, freeze, or display glitch, use \"Copy diagnostics\" or \"Export and share diagnostics\" first, then describe the problem in the feedback group.\n" +
                "Diagnostics include the version, device model, and relevant runtime state, which helps developers locate issues faster. If you can also include the steps to reproduce, even better.\n" +
                "The About page also offers \"Check for updates now\". You can check manually whenever you want to try a new version; and if you would rather the app did not check automatically, you can turn off \"Check for updates automatically\".\n" +
                "That wraps up the introduction.\n" +
                "We hope this port lets you enjoy eraTW on your phone too.\n" +
                "There are many more stories in Gensokyo, so take your time exploring.\n" +
                "**Have fun!**",
            prevButton = "Previous",
            nextButton = "Next",
            startButton = "Start Game",
            closeAction = "Close Guide",
            revisitTitle = "Beginner's Guide",
            revisitHint = "The Android port feature guide and playing tips shown on first launch, which you can revisit here.",
            revisitButton = "Read Again",
        )

        private val JA = TutorialCopy(
            lang = SettingsLanguage.JA,
            bookTitle = "eraTW 移植版・初心者ガイド",
            welcomeTitle = "ようこそ eraTW へ！",
            welcomeBody = "eraTW Android 移植版へようこそ！\n" +
                "eraTW が初めてなら、かなり大きな東方 Project の二次創作テキストアドベンチャーゲームだと思ってください。\n" +
                "この版は、もともとパソコンで動いていた eraTW を Android スマホに移したものです。スマホ向けに Emuera エンジンを書き直したので、めんどうなエミュレーターなしで、できるだけ元の遊び心地を残しています。\n" +
                "スマホで動くようにしただけでなく、スマホで欲しくなる機能もいくつか足しました。**セーブデータの読み込みと書き出し、背景画像と音楽の取り込み、サードパーティ口上パックのインストール**、それにバージョン確認やクラッシュ診断などです。\n" +
                "これらの機能は、ゲーム画面右上の「設定」から開けます。\n" +
                "次に、セーブデータ、背景素材、口上パックの使い方を順に紹介し、最後に遊ぶときのちょっとしたコツと便利なリンクをまとめます。\n" +
                "読むときの目安です。*黄色の文字* は特に気をつけてほしいキーワード、`青い文字` はそのまま探せるフォルダ名やファイル名です。\n" +
                "このガイドは、初回インストールのあとに一度だけ自動で表示されます。あとから読み返したいときは、「設定 → について」からいつでも開けます。\n" +
                "もちろん、もう早く遊びたいなら、右上の閉じるボタンを押しても大丈夫です。ガイドは消えません。",
            setupTitle = "「設定」メニューを知ろう",
            setupBody = "ゲーム画面の右上が「設定」の入口です。開くと四つのセクションがあります。\n" +
                "- **移植版の機能**は、セーブデータの読み込みや書き出しなど、Android 移植版だけの機能です。\n" +
                "- **元のエンジン**は、元のゲームエンジンについての設定です。\n" +
                "- **素材とパッチ**は、背景画像、BGM、サードパーティ口上パックの取り込みです。\n" +
                "- **について・フィードバック**は、バージョン情報の確認、新しい版のチェック、フィードバック窓口の確認ができます。\n" +
                "このあと何度も使う入口なので、だいたいの場所だけ覚えておけば十分です。覚え込む必要はありません。",
            savesTitle = "セーブデータの読み込みと書き出し",
            savesBody = "せっかく遊んで進めたデータ、なくしてしまっては一大事です。\n" +
                "移植版はパソコン版とセーブデータをやり取りできます。どちらも同じ形式を使っているからです。パソコンで進めたデータをスマホへ、スマホのセーブをパソコンへ、どちらもここからできます。\n" +
                "# どこで操作する？\n" +
                "「設定 → 移植版の機能 → セーブ」を開いてください。セーブの管理はこのセクションの一番上にあります。\n" +
                "# セーブを読み込む\n" +
                "1. 「セーブファイルを選ぶ」を押します。\n" +
                "2. 端末のファイル選択画面で、読み込みたいセーブを探します。\n" +
                "3. 対応する `.sav` ファイルを選べば完了です。一度に複数選ぶこともできます。\n" +
                "パソコン版の各セーブ枠と、全体セーブ `global.sav` のどちらも読み込めます。ファイルがどのセーブ枠のものかはアプリが自動で判断するので、自分で指定する必要はありません。\n" +
                "読み込み先にすでにセーブがあるときは、上書きするかどうかを先に聞かれます。確認するまでは元のセーブはそのままです。ファイルの選択を誤っても、慌てずに選び直せば大丈夫です。\n" +
                "# セーブを書き出す\n" +
                "「セーブをまとめて書き出す」を押すと、すべてのセーブ枠と全体セーブをひとつの ZIP ファイルにまとめて、指定した場所に書き出します。\n" +
                "機種を切り替えるときやゲームを入れ直すとき、友だちに自分の進み具合を渡したいときにも、この ZIP ファイルが使えます。\n" +
                "# いくつかの注意\n" +
                "- 機種を切り替えるときやゲームを入れ直す前に、まず一度セーブを書き出しておきましょう。\n" +
                "- 人からもらったセーブを読み込む前にも、自分のデータを先に控えておくのがおすすめです。\n" +
                "- 全体セーブと通常のセーブ枠は別々に残りますが、まとめて書き出すと全部入ります。\n" +
                "- 書き出した ZIP はセーブファイルごとに整理されています。機種を切り替えるときは展開して、必要な `.sav` ファイルをまとめて選んで読み込んでください。\n" +
                "定期的に控える習慣をつけましょう。やり直すより、数秒かけて控えておくほうがずっと楽です。",
            mediaTitle = "背景画像と BGM",
            mediaBody = "元の画面を長く見ていると、少し別の気分を味わいたくなりませんか？ 好きな音楽を流したい、というのもあるでしょう。\n" +
                "移植版は背景画像と BGM を自分で取り込めます。ゲームのフォルダを手で直す必要も、自分でまとめる手間もありません。\n" +
                "この機能は「設定 → 素材とパッチ」にまとまっています。\n" +
                "# 背景画像を取り込む\n" +
                "「背景画像を取り込む」カードを探して、次のどれかを選べます。\n" +
                "- 画像ファイルを 1 枚、または複数枚。\n" +
                "- 画像が入ったフォルダをまるごと。\n" +
                "- 公式の背景画像セット（`.zip` / `.7z`）。\n" +
                "取り込んだ画像は `resources/背景图/` フォルダに入ります。\n" +
                "公式セットを使うときは、画像をばらさずセットのまま取り込むのがおすすめです。セットには `BGI.csv` というインデックスファイルも入っていて、これがないと画像をうまく見分けられないことがあります。\n" +
                "インデックスファイルがない場合も、ファイル名からインデックスを自動で作ろうとします。ただしひとつ条件があります。**画像のファイル名がゲームの決めた命名ルールに合っていること**、つまり「地点_季节_时间」の形です。名前が合わないと、ゲームがその画像を見つけられないことがあります。\n" +
                "# BGM を取り込む\n" +
                "「BGM を取り込む」カードを探して、`.mp3` ファイルかフォルダをまるごと選んでください。\n" +
                "取り込んだ曲は、地点ごとに `sound/游戏bgm/` の下の対応するフォルダに入ります。特別な事情があれば、入れる場所を手で指定することもできます。\n" +
                "なお、ファイル名を前もって全部数字に直す必要はありません。ファイル名が 0-9 でない曲は、アプリが自動で番号を割り振るので、整理の手間がかなり省けます。\n" +
                "# 取り込んだあと、起動し直す必要はある？\n" +
                "ここだけは別です。\n" +
                "- **背景画像**は、ゲームを起動し直さないと反映されません。\n" +
                "- **BGM**は、起動し直す必要がなく、次に再生されるときから反映されます。\n" +
                "取り込みが終わるたびに、カードには取り込めたファイル数と飛ばしたファイルが表示されます。素材がうまく出ないときは、まずここの結果を見てみてください。",
            koujoTitle = "サードパーティ口上パックを入れる",
            koujoBody = "eraTW のコミュニティにしばらくいれば、「口上」という言葉を聞いたことがあるでしょう。\n" +
                "かんたんに言えば、口上はプレイヤーが作ったキャラクターの会話スクリプトです。書き方も作者ごとにそれぞれで、元のゲームにない会話を足すものもあれば、キャラクターのやり取りをまるごと新しくするものもあります。\n" +
                "口上パックを入れると、そのキャラクターの会話が作者の書いた内容になることがあります。これも eraTW コミュニティの大きな楽しみのひとつです。\n" +
                "# 入れ方は？\n" +
                "「設定 → 素材とパッチ → パッチ（MOD）の取り込み」を開き、「パッチの取り込みを開く」を押します。\n" +
                "選べるのは次のとおりです。\n" +
                "- `.ERB/.ERH` の口上ソースファイル。\n" +
                "- 口上ファイルが入った ZIP セット。\n" +
                "- 一度に複数のファイル。\n" +
                "取り込んだソースファイルは `mods` フォルダに入り、ゲームのパッチとして自動でコンパイルされます。\n" +
                "**注意。入れたあとは、ゲームを起動し直さないと反映されません。**\n" +
                "# 口上パックのコンパイルに失敗したら？\n" +
                "それほど心配はいりません。\n" +
                "サードパーティの口上パックには、特殊な書き方を使っていたり、元のゲームの関数に頼っていたりして、移植版のコンパイルをそのまま通らないものもあります。\n" +
                "そういうときは、アプリがエラー報告を作り、どのファイルで何が起きたのか、理由とあわせて知らせてくれます。\n" +
                "ほかの部分はそのままコンパイルできて動きます。ひとつのファイルが失敗したからといって、ゲーム全体が起動しなくなることはありません。\n" +
                "# 入れるときのコツ\n" +
                "- 初めて口上パックを入れるときは、小さめのセットから始めましょう。\n" +
                "- できれば一度にひとそろいだけ入れ、ゲームで問題ないと確かめてから次を入れてください。何かあったときも原因を追いやすくなります。\n" +
                "- 口上パックが新しくするのは主にキャラクターの会話で、セーブデータそのものに手を入れることはありません。好みに合わせて入れたり外したりできます。\n" +
                "もうひとつ。大きな口上パックは初回にかなり多くのファイルをコンパイルするので、起動待ちがいつもより長くなります。コンパイルの進み具合が出たら、終わるまで気長に待ちましょう。",
            tipsTitle = "遊ぶときのちょっとしたコツ",
            tipsBody = "eraTW を初めて遊ぶなら、あまりの内容の多さにとまどうかもしれません。ここでは実用的なコツをいくつかまとめました。遠回りを減らせるといいです。\n" +
                "1. **時間の使い方に気をつける**。ゲームの行動はほとんどが時間を進め、時間帯によって起きることも別です。ただし主人公には時間を止める力があるので、うまく使えばゲーム内の時間を使わずに用事を済ませられます。\n" +
                "2. **最初のうちは少しずつためる**。掃除や仕事は良いスタートになります。お金と人脈をためておくと、あとで道具を買ったり毎日の出費をまかなったりがぐっと楽になります。\n" +
                "3. **技能を上げるのも忘れずに**。戦闘、音楽、料理、教養などの技能はどれも育てる価値があります。技能が上がると、できるやり取りやイベントが少しずつ増え、仕事の成功率も上がります。\n" +
                "4. **キャラクターとたくさんやり取りする**。話しかける、贈り物をする、どちらも好感を上げる方法です。好感度やほかの条件がそろうと、新しい間柄の進展や口上、特別なイベントが少しずつ開けていきます。\n" +
                "5. **こまめにセーブする**。これは本当に大事です。内容が多いぶん、歩き回っている途中で思わぬことが起きます。定期的にセーブする習慣をつけ、外からセーブを読み込む前にも自分のデータを控えておきましょう。\n" +
                "6. **同じ場所にばかりいない**。場所や時間帯によって、出会うキャラクターやイベントも別のものになります。用がないときは地図をあちこち歩いてみると、思いがけないものに出会えるかもしれません。\n" +
                "7. **不具合が出ても、すぐデータを消さない**。急に調子がおかしくなったり、固まったり、画面がおかしくなったりしたら、まずアプリの起動し直しを試してください。それでも直らないときは、「設定 → について」の「診断をコピー」か「診断を書き出して共有」で、診断情報を開発者に渡せます。「壊れた」とだけ伝えるより、診断情報があったほうが原因を探しやすくなります。",
            resourcesTitle = "さらにリソースとフィードバック窓口",
            resourcesBody = "ひとりで遊んでももちろん楽しいですが、eraTW の面白さはプレイヤーのコミュニティから来る部分も大きいです。\n" +
                "もっと口上やセット、ゲームの素材を探したいとき、ほかのプレイヤーの攻略を見たいときは、百度貼吧の **「eratw吧」** をのぞいてみてください。いろいろな人が共有した内容があり、交流もぜひどうぞ。\n" +
                "# 困ったとき、最新版を知りたいとき\n" +
                "「設定 → について」を開くと、次のものが見つかります。\n" +
                "- 今のゲームのバージョン情報。\n" +
                "- プロジェクトのリポジトリとリンク集。\n" +
                "- Bug 報告グループの番号（そのままコピーできます）。\n" +
                "- 診断情報の書き出し機能。\n" +
                "- 新しい版のチェック。\n" +
                "強制終了、黒い画面、固まる、画面の異常などが出たら、まず「診断をコピー」か「診断を書き出して共有」を使い、そのうえで報告グループで症状を説明してください。\n" +
                "診断情報にはバージョン、端末の型番、実行時の状況が入っていて、開発者が原因を早く見つける助けになります。報告のときに、同じ症状が出る手順も添えられると、なお良いです。\n" +
                "また、についてのページには「今すぐ更新を確認」もあります。新しい版を試したいときはいつでも手動で確認できます。自動で確認してほしくないときは、「更新の自動確認」を切っておけます。\n" +
                "さて、紹介はここまでです。\n" +
                "この移植版で、スマホでも eraTW の楽しさを味わってもらえたらうれしいです。\n" +
                "幻想郷の物語はまだまだ続きます。ゆっくり進めていきましょう。\n" +
                "**どうか、楽しんでください！**",
            prevButton = "前へ",
            nextButton = "次へ",
            startButton = "ゲームを始める",
            closeAction = "ガイドを閉じる",
            revisitTitle = "初心者ガイド",
            revisitHint = "初回起動時に表示される移植版機能のガイドと遊び方のコツです。ここから読み返せます。",
            revisitButton = "読み返す",
        )

        /** 与设置页 `text.lang` 同源的单路取值。 */
        fun of(lang: SettingsLanguage): TutorialCopy = when (lang) {
            SettingsLanguage.ZH -> ZH
            SettingsLanguage.EN -> EN
            SettingsLanguage.JA -> JA
        }
    }
}
