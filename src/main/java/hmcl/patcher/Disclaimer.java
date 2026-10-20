package hmcl.patcher;

/// The disclaimer that every user has to acknowledge before the tool modifies anything.
public final class Disclaimer {

    /// Full disclaimer text, shown in the command line and in the graphical window.
    public static final String TEXT = """
            免责条款
            ------------------------------------------------------------
            1. 本工具仅供个人学习、研究使用，只修改你本机上的 HMCL 副本。
            2. 本工具与 HMCL 官方（HMCL-dev）无关，未经其授权或认可。
            3. 修改后的 HMCL 会失去官方签名，被识别为“非官方构建”：自动更新被禁用，
               登录微软账号时会显示提示，其它功能不受影响。
            4. 使用本工具可能违反你所在地区的法律法规或相关服务条款，请自行判断并承担全部风险。
            5. 本工具按“现状”提供，不提供任何明示或暗示的担保；因使用本工具造成的任何直接或
               间接损失（包括但不限于数据丢失、账号异常、无法更新）均由使用者自行承担。
            6. 请在使用前自行备份原始文件；继续使用即表示你已阅读、理解并同意以上全部条款。
            """;

    private Disclaimer() {
    }

    /// Prints the disclaimer to standard output.
    public static void print() {
        System.out.println(TEXT);
        System.out.println();
    }
}
