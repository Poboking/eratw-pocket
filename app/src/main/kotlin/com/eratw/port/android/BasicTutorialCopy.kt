package com.eratw.port.android

import java.util.concurrent.ConcurrentHashMap

/**
 * SHELL-PROFILE 的 `tutorialMode=basic` 文案表：只保留壳层基础功能介绍。
 *
 * 与 [TutorialCopy] 的七页同形状（23 构造属性），因此直接复用现有形状闸；
 * 但正文不含任何 eraTW 语料专名（eraTW / 口上 / 贴吧 / 东方 Project / 幻想乡 等），
 * 游戏名一律由配置注入。第三方 era 游戏出包时用它替换原七页，eraTW 缺省路径不受影响。
 */
internal object BasicTutorialCopy {
    private val cache = ConcurrentHashMap<String, TutorialCopy>()

    fun of(lang: SettingsLanguage, gameName: String): TutorialCopy =
        cache.getOrPut("${lang.name}\u0000$gameName") {
            when (lang) {
                SettingsLanguage.ZH -> zh(gameName)
                SettingsLanguage.EN -> en(gameName)
                SettingsLanguage.JA -> ja(gameName)
            }
        }

    private fun zh(g: String) = TutorialCopy(
        lang = SettingsLanguage.ZH,
        bookTitle = "$g · 使用说明",
        welcomeTitle = "欢迎来到 $g",
        welcomeBody = "欢迎使用 $g 安卓版！\n" +
            "这里只保留最基础的功能介绍，具体玩法由随包游戏内容决定。\n" +
            "本说明只在首次安装后自动弹出一次，之后可从「设置 → 关于」重新查阅。",
        setupTitle = "设置入口",
        setupBody = "游戏画面右上角是「设置」。\n" +
            "- **功能**：存档导入、导出等安卓壳功能。\n" +
            "- **引擎**：原版引擎相关设置。\n" +
            "- **素材与补丁**：背景图片、音乐与扩展内容导入。\n" +
            "- **关于**：版本信息、诊断与更新检查。",
        savesTitle = "存档导入与导出",
        savesBody = "游戏进度保存在本机应用数据里。\n" +
            "# 导入\n" +
            "1. 打开「设置 → 功能 → 存档」。\n" +
            "2. 选择本机里的存档文件。\n" +
            "# 导出\n" +
            "点击一键导出，可把全部存档打包带走。换机或重装前建议先导出一次。",
        mediaTitle = "背景图片与音乐",
        mediaBody = "背景图片与背景音乐可在「设置 → 素材与补丁」里导入。\n" +
            "- 图片导入后通常需要重启才能生效。\n" +
            "- 音乐在下一次播放时生效。\n" +
            "导入结果会显示成功与跳过的数量，方便核对。",
        koujoTitle = "补丁与扩展内容",
        koujoBody = "扩展内容通过「设置 → 素材与补丁」里的补丁导入入口安装。\n" +
            "- 支持源码文件与压缩包。\n" +
            "- 安装完成后通常需要重启游戏。\n" +
            "第三方内容由各自作者维护，遇到编译失败可先查看生成的错误报告。",
        tipsTitle = "基础操作建议",
        tipsBody = "1. **先看标题菜单**：从新游戏或读取存档开始。\n" +
            "2. **留意提示行**：选项与输入都通过屏上提示推进。\n" +
            "3. **经常存档**：探索前留一份进度，导入外部存档前先备份。\n" +
            "4. **遇到问题先重启**：仍不正常时，用「设置 → 关于」里的诊断导出功能。",
        resourcesTitle = "关于与诊断",
        resourcesBody = "「设置 → 关于」提供版本信息、项目地址与反馈入口。\n" +
            "- 复制诊断或导出诊断，便于反馈问题时附上现场。\n" +
            "- 立即检查更新可手动获取新版本。\n" +
            "祝你在 $g 中玩得开心。",
        prevButton = "上一页",
        nextButton = "下一页",
        startButton = "开始游戏",
        closeAction = "关闭说明",
        revisitTitle = "使用说明",
        revisitHint = "本应用的基础功能说明，可在此重新查阅。",
        revisitButton = "重新查阅",
    )

    private fun en(g: String) = TutorialCopy(
        lang = SettingsLanguage.EN,
        bookTitle = "$g · Quick Guide",
        welcomeTitle = "Welcome to $g",
        welcomeBody = "Welcome to the Android shell for $g!\n" +
            "This guide keeps only the basic app features. Gameplay comes from the bundled game data.\n" +
            "It appears once after installation. Reopen it any time from Settings > About.",
        setupTitle = "Settings Entry",
        setupBody = "Settings sits in the top-right corner of the game screen.\n" +
            "- **Features**: save import and export.\n" +
            "- **Engine**: original engine settings.\n" +
            "- **Assets and patches**: images, music and extensions.\n" +
            "- **About**: version, diagnostics and updates.",
        savesTitle = "Save Import and Export",
        savesBody = "Progress is stored in the app's own data directory.\n" +
            "# Import\n" +
            "1. Open Settings > Features > Saves.\n" +
            "2. Pick a save file from this device.\n" +
            "# Export\n" +
            "Use one-tap export to bundle every slot. Export before reinstalling or switching devices.",
        mediaTitle = "Images and Music",
        mediaBody = "Import images and music under Settings > Assets and patches.\n" +
            "- Images usually need a restart to take effect.\n" +
            "- Music plays from the next trigger.\n" +
            "The result shows how many files were accepted or skipped.",
        koujoTitle = "Patches and Extensions",
        koujoBody = "Install extensions from the patch import entry under Settings > Assets and patches.\n" +
            "- Source files and archives are supported.\n" +
            "- A restart is usually needed after installation.\n" +
            "Third-party content is maintained by its authors; check the generated report if import fails.",
        tipsTitle = "Basic Tips",
        tipsBody = "1. **Start from the title menu**: new game or load.\n" +
            "2. **Follow on-screen prompts**: choices and input use the prompt line.\n" +
            "3. **Save often**: back up before importing outside saves.\n" +
            "4. **Restart first**: if something breaks, export diagnostics from Settings > About.",
        resourcesTitle = "About and Diagnostics",
        resourcesBody = "Settings > About holds version info, project links and feedback entries.\n" +
            "- Copy or export diagnostics when reporting a problem.\n" +
            "- Check for updates manually at any time.\n" +
            "Enjoy $g.",
        prevButton = "Previous",
        nextButton = "Next",
        startButton = "Start",
        closeAction = "Close guide",
        revisitTitle = "Quick Guide",
        revisitHint = "Basic app features. Reopen it here any time.",
        revisitButton = "Reopen",
    )

    private fun ja(g: String) = TutorialCopy(
        lang = SettingsLanguage.JA,
        bookTitle = "$g · 簡易ガイド",
        welcomeTitle = "$g へようこそ",
        welcomeBody = "$g の Android 版へようこそ。\n" +
            "ここではアプリの基本的な機能だけを案内します。遊び方は同梱ゲームによります。\n" +
            "初回インストール時に一度だけ表示されます。後から「設定 → 情報」で再表示できます。",
        setupTitle = "設定の入口",
        setupBody = "画面右上に「設定」があります。\n" +
            "- **機能**：セーブの読み込みと書き出し。\n" +
            "- **エンジン**：元のエンジン設定。\n" +
            "- **素材とパッチ**：画像、音楽、拡張の読み込み。\n" +
            "- **情報**：バージョン、診断、更新確認。",
        savesTitle = "セーブの読み書き",
        savesBody = "進行状況はアプリ内に保存されます。\n" +
            "# 読み込み\n" +
            "1. 「設定 → 機能 → セーブ」を開きます。\n" +
            "2. 端末内のセーブファイルを選びます。\n" +
            "# 書き出し\n" +
            "一括書き出しですべてのスロットをまとめられます。機種変更前に行ってください。",
        mediaTitle = "背景画像と音楽",
        mediaBody = "背景画像と音楽は「設定 → 素材とパッチ」で読み込みます。\n" +
            "- 画像は再起動後に反映される場合があります。\n" +
            "- 音楽は次の再生時から反映されます。\n" +
            "結果に成功と除外の件数が表示されます。",
        koujoTitle = "パッチと拡張",
        koujoBody = "拡張は「設定 → 素材とパッチ」のパッチ読み込みから入れます。\n" +
            "- ソース文件と圧縮包に対応します。\n" +
            "- 導入後は再起動が必要です。\n" +
            "内容は各作者が管理します。失敗したら生成された報告を確認してください。",
        tipsTitle = "基本の操作",
        tipsBody = "1. **タイトルから始める**：新規ゲームかロードを選びます。\n" +
            "2. **画面の案内を見る**：選択と入力は提示行で進みます。\n" +
            "3. **こまめに保存**：外部セーブの前には必ずバックアップを取ります。\n" +
            "4. **まず再起動**：直らない場合は「設定 → 情報」から診断を書き出します。",
        resourcesTitle = "情報と診断",
        resourcesBody = "「設定 → 情報」にバージョン、リンク、連絡先があります。\n" +
            "- 不具合報告には診断のコピーまたは書き出しを添えてください。\n" +
            "- 更新はいつでも手動で確認できます。\n" +
            "$g を楽しんでください。",
        prevButton = "前へ",
        nextButton = "次へ",
        startButton = "開始",
        closeAction = "ガイドを閉じる",
        revisitTitle = "簡易ガイド",
        revisitHint = "アプリの基本機能をいつでも再確認できます。",
        revisitButton = "再表示",
    )
}
