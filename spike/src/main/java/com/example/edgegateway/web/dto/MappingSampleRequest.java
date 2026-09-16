package com.example.edgegateway.web.dto;

/**
 * 「点保存」时手里那份样例要落到哪一格（#119 规则 2）：来源与目标各一格，各自带上归属。
 *
 * <ul>
 *   <li>{@code json} 非空 = 手里这份是**粘的** → 存下来（同一格覆盖）；</li>
 *   <li>{@code json} 空 = 手里这份是**探测来的** → 把这一格删掉（下回打开就回到探测那条路）；</li>
 *   <li>整个一边为 {@code null} = 这一边跟这次保存无关（来源侧压根没有样例 / 没有写目标动作），
 *       库里那一格不动。</li>
 * </ul>
 *
 * <p>归属键（来源 = 步骤、目标 = 写目标动作 id）就是库里那两格的键：换过来源步骤再保存，按**保存那一刻**
 * 归到哪一步落那一格（#119「什么时候写」）。</p>
 */
public record MappingSampleRequest(SourceSampleSave source, TargetSampleSave target) {

    /** 来源那一格：{@code step} = 动作序号（0 = 任务输入）。 */
    public record SourceSampleSave(Integer step, String json) {
    }

    /** 目标那一格：{@code actionId} = 写目标动作 id。 */
    public record TargetSampleSave(Long actionId, String json) {
    }
}
