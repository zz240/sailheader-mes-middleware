package com.example.edgegateway.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.edgegateway.mapper.ActionMapper;
import com.example.edgegateway.mapper.MappingSampleMapper;
import com.example.edgegateway.model.Action;
import com.example.edgegateway.model.MappingSample;
import com.example.edgegateway.model.MappingSampleKind;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 编辑器里粘进来的那份样例怎么落库（#119）：**一格一份原文**，别的什么都不存（见
 * {@link MappingSample}）。
 *
 * <p>两条规则就是这一票的全部（ADR-0012 决策 5 修订）：</p>
 * <ol>
 *   <li><b>打开时</b>：这一步 / 这个写目标动作存过一份粘的 → 用存的那份；没存过 → 探测那份（那条路
 *       一个字不变）。读在 {@link FieldMappingEditorService#context}，从这儿只取原文；</li>
 *   <li><b>保存时</b>：手里是粘的 → 存下来（同一格覆盖）；手里是**探测来的 → 把这一格删掉**。
 *       写就是这里的 {@link #save}。</li>
 * </ol>
 *
 * <p>所以「保存什么，下次打开就是什么」；粘了但不保存（取消）什么都不留，与编辑器里其它东西一个口径。
 * 探测那份不落库：它随时能再取一次，存它等于把同一份报文在库里放两遍。</p>
 *
 * <p>删除动作用 {@link #clearOfAction}、删任务用 {@link #clearOfJob}：**样例行**顺手带走；
 * 删任务时**动作行**（{@code job_action}）也一并带走——两处都不留孤儿行。</p>
 */
@Service
public class MappingSampleService {

    private final MappingSampleMapper mapper;
    private final ActionMapper actionMapper;

    public MappingSampleService(MappingSampleMapper mapper, ActionMapper actionMapper) {
        this.mapper = mapper;
        this.actionMapper = actionMapper;
    }

    /** 某一格存过的那份原文；没存过是 null（调用方据此退回探测那条路）。 */
    public String stored(Long jobId, MappingSampleKind kind, Long refId) {
        MappingSample row = find(jobId, kind, refId);
        return row == null ? null : row.getSampleJson();
    }

    /**
     * 点「保存」时写这一格：{@code json} 非空 = 手里那份是粘的（同一格覆盖）；空 = 手里那份是探测来的
     * （把这一格删掉）。
     */
    public void save(Long jobId, MappingSampleKind kind, Long refId, String json) {
        requireCell(jobId, kind, refId);
        if (json == null || json.isBlank()) {
            clear(jobId, kind, refId);
            return;
        }
        MappingSample existing = find(jobId, kind, refId);
        LocalDateTime now = LocalDateTime.now();
        if (existing == null) {
            MappingSample row = new MappingSample();
            row.setJobId(jobId);
            row.setKind(kind);
            row.setRefId(refId);
            row.setSampleJson(json);
            row.setUpdatedAt(now);
            mapper.insert(row);
            return;
        }
        existing.setSampleJson(json);
        existing.setUpdatedAt(now);
        mapper.updateById(existing);
    }

    /** 删掉这一格；这一格本来就没存过时是一次空删（删两次与删一次一个样）。 */
    public void clear(Long jobId, MappingSampleKind kind, Long refId) {
        requireCell(jobId, kind, refId);
        mapper.delete(new LambdaQueryWrapper<MappingSample>()
                .eq(MappingSample::getJobId, jobId)
                .eq(MappingSample::getKind, kind)
                .eq(MappingSample::getRefId, refId));
    }

    /**
     * 删动作时顺手清掉它的样例行：目标那格按**动作 id**（它就是写目标）、来源那格按**动作序号**
     * （它可能就是别人读的那一步）。
     */
    public void clearOfAction(Long jobId, Long actionId, Integer actionIndex) {
        if (actionId != null) {
            clear(jobId, MappingSampleKind.TARGET, actionId);
        }
        if (actionIndex != null) {
            clear(jobId, MappingSampleKind.SOURCE, actionIndex.longValue());
        }
    }

    /** 删任务时清掉整个任务的样例行和动作行。 */
    public void clearOfJob(Long jobId) {
        if (jobId == null) {
            return;
        }
        mapper.delete(new LambdaQueryWrapper<MappingSample>().eq(MappingSample::getJobId, jobId));
        actionMapper.delete(new LambdaQueryWrapper<Action>().eq(Action::getJobId, jobId));
    }

    private MappingSample find(Long jobId, MappingSampleKind kind, Long refId) {
        requireCell(jobId, kind, refId);
        return mapper.selectOne(new LambdaQueryWrapper<MappingSample>()
                .eq(MappingSample::getJobId, jobId)
                .eq(MappingSample::getKind, kind)
                .eq(MappingSample::getRefId, refId));
    }

    /** 一格的身份缺一不可：缺了就是调用方的错（不能静默写出一行谁都认不出来的样例）。 */
    private void requireCell(Long jobId, MappingSampleKind kind, Long refId) {
        if (jobId == null || kind == null || refId == null) {
            throw new IllegalArgumentException("样例行要有归属：任务、用途（来源/目标）、归属键都不能空");
        }
    }
}
