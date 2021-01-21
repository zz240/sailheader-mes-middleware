package com.sailheader.middleware.model;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class JobExample {
    protected String orderByClause;

    protected boolean distinct;

    protected List<JobExample.Criteria> oredCriteria;

    private Integer limit;

    private Long offset;

    public JobExample() {
        oredCriteria = new ArrayList<JobExample.Criteria>();
    }

    public void setOrderByClause(String orderByClause) {
        this.orderByClause = orderByClause;
    }

    public String getOrderByClause() {
        return orderByClause;
    }

    public void setDistinct(boolean distinct) {
        this.distinct = distinct;
    }

    public boolean isDistinct() {
        return distinct;
    }

    public List<JobExample.Criteria> getOredCriteria() {
        return oredCriteria;
    }

    public void or(JobExample.Criteria criteria) {
        oredCriteria.add(criteria);
    }

    public JobExample.Criteria or() {
        JobExample.Criteria criteria = createCriteriaInternal();
        oredCriteria.add(criteria);
        return criteria;
    }

    public JobExample.Criteria createCriteria() {
        JobExample.Criteria criteria = createCriteriaInternal();
        if (oredCriteria.size() == 0) {
            oredCriteria.add(criteria);
        }
        return criteria;
    }

    protected JobExample.Criteria createCriteriaInternal() {
        JobExample.Criteria criteria = new JobExample.Criteria();
        return criteria;
    }

    public void clear() {
        oredCriteria.clear();
        orderByClause = null;
        distinct = false;
    }

    public void setLimit(Integer limit) {
        this.limit = limit;
    }

    public Integer getLimit() {
        return limit;
    }

    public void setOffset(Long offset) {
        this.offset = offset;
    }

    public Long getOffset() {
        return offset;
    }

    protected abstract static class GeneratedCriteria {
        protected List<JobExample.Criterion> criteria;

        protected GeneratedCriteria() {
            super();
            criteria = new ArrayList<JobExample.Criterion>();
        }

        public boolean isValid() {
            return criteria.size() > 0;
        }

        public List<JobExample.Criterion> getAllCriteria() {
            return criteria;
        }

        public List<JobExample.Criterion> getCriteria() {
            return criteria;
        }

        protected void addCriterion(String condition) {
            if (condition == null) {
                throw new RuntimeException("Value for condition cannot be null");
            }
            criteria.add(new JobExample.Criterion(condition));
        }

        protected void addCriterion(String condition, Object value, String property) {
            if (value == null) {
                throw new RuntimeException("Value for " + property + " cannot be null");
            }
            criteria.add(new JobExample.Criterion(condition, value));
        }

        protected void addCriterion(String condition, Object value1, Object value2, String property) {
            if (value1 == null || value2 == null) {
                throw new RuntimeException("Between values for " + property + " cannot be null");
            }
            criteria.add(new JobExample.Criterion(condition, value1, value2));
        }

        public JobExample.Criteria andIdIsNull() {
            addCriterion("id is null");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andIdIsNotNull() {
            addCriterion("id is not null");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andIdEqualTo(Long value) {
            addCriterion("id =", value, "id");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andIdNotEqualTo(Long value) {
            addCriterion("id <>", value, "id");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andIdGreaterThan(Long value) {
            addCriterion("id >", value, "id");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andIdGreaterThanOrEqualTo(Long value) {
            addCriterion("id >=", value, "id");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andIdLessThan(Long value) {
            addCriterion("id <", value, "id");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andIdLessThanOrEqualTo(Long value) {
            addCriterion("id <=", value, "id");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andIdIn(List<Long> values) {
            addCriterion("id in", values, "id");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andIdNotIn(List<Long> values) {
            addCriterion("id not in", values, "id");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andIdBetween(Long value1, Long value2) {
            addCriterion("id between", value1, value2, "id");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andIdNotBetween(Long value1, Long value2) {
            addCriterion("id not between", value1, value2, "id");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andTypeIsNull() {
            addCriterion("\"type\" is null");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andTypeIsNotNull() {
            addCriterion("\"type\" is not null");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andTypeEqualTo(String value) {
            addCriterion("\"type\" =", value, "type");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andTypeNotEqualTo(String value) {
            addCriterion("\"type\" <>", value, "type");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andTypeGreaterThan(String value) {
            addCriterion("\"type\" >", value, "type");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andTypeGreaterThanOrEqualTo(String value) {
            addCriterion("\"type\" >=", value, "type");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andTypeLessThan(String value) {
            addCriterion("\"type\" <", value, "type");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andTypeLessThanOrEqualTo(String value) {
            addCriterion("\"type\" <=", value, "type");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andTypeLike(String value) {
            addCriterion("\"type\" like", value, "type");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andTypeNotLike(String value) {
            addCriterion("\"type\" not like", value, "type");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andTypeIn(List<String> values) {
            addCriterion("\"type\" in", values, "type");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andTypeNotIn(List<String> values) {
            addCriterion("\"type\" not in", values, "type");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andTypeBetween(String value1, String value2) {
            addCriterion("\"type\" between", value1, value2, "type");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andTypeNotBetween(String value1, String value2) {
            addCriterion("\"type\" not between", value1, value2, "type");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andSequenceIsNull() {
            addCriterion("\"sequence\" is null");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andSequenceIsNotNull() {
            addCriterion("\"sequence\" is not null");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andSequenceEqualTo(Long value) {
            addCriterion("\"sequence\" =", value, "sequence");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andSequenceNotEqualTo(Long value) {
            addCriterion("\"sequence\" <>", value, "sequence");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andSequenceGreaterThan(Long value) {
            addCriterion("\"sequence\" >", value, "sequence");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andSequenceGreaterThanOrEqualTo(Long value) {
            addCriterion("\"sequence\" >=", value, "sequence");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andSequenceLessThan(Long value) {
            addCriterion("\"sequence\" <", value, "sequence");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andSequenceLessThanOrEqualTo(Long value) {
            addCriterion("\"sequence\" <=", value, "sequence");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andSequenceIn(List<Long> values) {
            addCriterion("\"sequence\" in", values, "sequence");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andSequenceNotIn(List<Long> values) {
            addCriterion("\"sequence\" not in", values, "sequence");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andSequenceBetween(Long value1, Long value2) {
            addCriterion("\"sequence\" between", value1, value2, "sequence");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andSequenceNotBetween(Long value1, Long value2) {
            addCriterion("\"sequence\" not between", value1, value2, "sequence");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andEnabledIsNull() {
            addCriterion("enabled is null");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andEnabledIsNotNull() {
            addCriterion("enabled is not null");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andEnabledEqualTo(Boolean value) {
            addCriterion("enabled =", value, "enabled");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andEnabledNotEqualTo(Boolean value) {
            addCriterion("enabled <>", value, "enabled");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andEnabledGreaterThan(Boolean value) {
            addCriterion("enabled >", value, "enabled");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andEnabledGreaterThanOrEqualTo(Boolean value) {
            addCriterion("enabled >=", value, "enabled");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andEnabledLessThan(Boolean value) {
            addCriterion("enabled <", value, "enabled");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andEnabledLessThanOrEqualTo(Boolean value) {
            addCriterion("enabled <=", value, "enabled");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andEnabledIn(List<Boolean> values) {
            addCriterion("enabled in", values, "enabled");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andEnabledNotIn(List<Boolean> values) {
            addCriterion("enabled not in", values, "enabled");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andEnabledBetween(Boolean value1, Boolean value2) {
            addCriterion("enabled between", value1, value2, "enabled");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andEnabledNotBetween(Boolean value1, Boolean value2) {
            addCriterion("enabled not between", value1, value2, "enabled");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andPinyinIsNull() {
            addCriterion("pinyin is null");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andPinyinIsNotNull() {
            addCriterion("pinyin is not null");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andPinyinEqualTo(String value) {
            addCriterion("pinyin =", value, "pinyin");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andPinyinNotEqualTo(String value) {
            addCriterion("pinyin <>", value, "pinyin");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andPinyinGreaterThan(String value) {
            addCriterion("pinyin >", value, "pinyin");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andPinyinGreaterThanOrEqualTo(String value) {
            addCriterion("pinyin >=", value, "pinyin");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andPinyinLessThan(String value) {
            addCriterion("pinyin <", value, "pinyin");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andPinyinLessThanOrEqualTo(String value) {
            addCriterion("pinyin <=", value, "pinyin");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andPinyinLike(String value) {
            addCriterion("pinyin like", value, "pinyin");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andPinyinNotLike(String value) {
            addCriterion("pinyin not like", value, "pinyin");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andPinyinIn(List<String> values) {
            addCriterion("pinyin in", values, "pinyin");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andPinyinNotIn(List<String> values) {
            addCriterion("pinyin not in", values, "pinyin");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andPinyinBetween(String value1, String value2) {
            addCriterion("pinyin between", value1, value2, "pinyin");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andPinyinNotBetween(String value1, String value2) {
            addCriterion("pinyin not between", value1, value2, "pinyin");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andEnUsNameIsNull() {
            addCriterion("en_us_name is null");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andEnUsNameIsNotNull() {
            addCriterion("en_us_name is not null");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andEnUsNameEqualTo(String value) {
            addCriterion("en_us_name =", value, "enUsName");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andEnUsNameNotEqualTo(String value) {
            addCriterion("en_us_name <>", value, "enUsName");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andEnUsNameGreaterThan(String value) {
            addCriterion("en_us_name >", value, "enUsName");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andEnUsNameGreaterThanOrEqualTo(String value) {
            addCriterion("en_us_name >=", value, "enUsName");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andEnUsNameLessThan(String value) {
            addCriterion("en_us_name <", value, "enUsName");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andEnUsNameLessThanOrEqualTo(String value) {
            addCriterion("en_us_name <=", value, "enUsName");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andEnUsNameLike(String value) {
            addCriterion("en_us_name like", value, "enUsName");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andEnUsNameNotLike(String value) {
            addCriterion("en_us_name not like", value, "enUsName");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andEnUsNameIn(List<String> values) {
            addCriterion("en_us_name in", values, "enUsName");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andEnUsNameNotIn(List<String> values) {
            addCriterion("en_us_name not in", values, "enUsName");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andEnUsNameBetween(String value1, String value2) {
            addCriterion("en_us_name between", value1, value2, "enUsName");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andEnUsNameNotBetween(String value1, String value2) {
            addCriterion("en_us_name not between", value1, value2, "enUsName");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andZhCnNameIsNull() {
            addCriterion("zh_cn_name is null");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andZhCnNameIsNotNull() {
            addCriterion("zh_cn_name is not null");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andZhCnNameEqualTo(String value) {
            addCriterion("zh_cn_name =", value, "zhCnName");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andZhCnNameNotEqualTo(String value) {
            addCriterion("zh_cn_name <>", value, "zhCnName");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andZhCnNameGreaterThan(String value) {
            addCriterion("zh_cn_name >", value, "zhCnName");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andZhCnNameGreaterThanOrEqualTo(String value) {
            addCriterion("zh_cn_name >=", value, "zhCnName");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andZhCnNameLessThan(String value) {
            addCriterion("zh_cn_name <", value, "zhCnName");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andZhCnNameLessThanOrEqualTo(String value) {
            addCriterion("zh_cn_name <=", value, "zhCnName");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andZhCnNameLike(String value) {
            addCriterion("zh_cn_name like", value, "zhCnName");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andZhCnNameNotLike(String value) {
            addCriterion("zh_cn_name not like", value, "zhCnName");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andZhCnNameIn(List<String> values) {
            addCriterion("zh_cn_name in", values, "zhCnName");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andZhCnNameNotIn(List<String> values) {
            addCriterion("zh_cn_name not in", values, "zhCnName");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andZhCnNameBetween(String value1, String value2) {
            addCriterion("zh_cn_name between", value1, value2, "zhCnName");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andZhCnNameNotBetween(String value1, String value2) {
            addCriterion("zh_cn_name not between", value1, value2, "zhCnName");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andEnUsContentIsNull() {
            addCriterion("en_us_content is null");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andEnUsContentIsNotNull() {
            addCriterion("en_us_content is not null");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andEnUsContentEqualTo(String value) {
            addCriterion("en_us_content =", value, "enUsContent");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andEnUsContentNotEqualTo(String value) {
            addCriterion("en_us_content <>", value, "enUsContent");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andEnUsContentGreaterThan(String value) {
            addCriterion("en_us_content >", value, "enUsContent");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andEnUsContentGreaterThanOrEqualTo(String value) {
            addCriterion("en_us_content >=", value, "enUsContent");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andEnUsContentLessThan(String value) {
            addCriterion("en_us_content <", value, "enUsContent");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andEnUsContentLessThanOrEqualTo(String value) {
            addCriterion("en_us_content <=", value, "enUsContent");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andEnUsContentLike(String value) {
            addCriterion("en_us_content like", value, "enUsContent");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andEnUsContentNotLike(String value) {
            addCriterion("en_us_content not like", value, "enUsContent");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andEnUsContentIn(List<String> values) {
            addCriterion("en_us_content in", values, "enUsContent");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andEnUsContentNotIn(List<String> values) {
            addCriterion("en_us_content not in", values, "enUsContent");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andEnUsContentBetween(String value1, String value2) {
            addCriterion("en_us_content between", value1, value2, "enUsContent");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andEnUsContentNotBetween(String value1, String value2) {
            addCriterion("en_us_content not between", value1, value2, "enUsContent");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andZhCnContentIsNull() {
            addCriterion("zh_cn_content is null");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andZhCnContentIsNotNull() {
            addCriterion("zh_cn_content is not null");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andZhCnContentEqualTo(String value) {
            addCriterion("zh_cn_content =", value, "zhCnContent");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andZhCnContentNotEqualTo(String value) {
            addCriterion("zh_cn_content <>", value, "zhCnContent");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andZhCnContentGreaterThan(String value) {
            addCriterion("zh_cn_content >", value, "zhCnContent");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andZhCnContentGreaterThanOrEqualTo(String value) {
            addCriterion("zh_cn_content >=", value, "zhCnContent");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andZhCnContentLessThan(String value) {
            addCriterion("zh_cn_content <", value, "zhCnContent");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andZhCnContentLessThanOrEqualTo(String value) {
            addCriterion("zh_cn_content <=", value, "zhCnContent");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andZhCnContentLike(String value) {
            addCriterion("zh_cn_content like", value, "zhCnContent");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andZhCnContentNotLike(String value) {
            addCriterion("zh_cn_content not like", value, "zhCnContent");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andZhCnContentIn(List<String> values) {
            addCriterion("zh_cn_content in", values, "zhCnContent");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andZhCnContentNotIn(List<String> values) {
            addCriterion("zh_cn_content not in", values, "zhCnContent");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andZhCnContentBetween(String value1, String value2) {
            addCriterion("zh_cn_content between", value1, value2, "zhCnContent");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andZhCnContentNotBetween(String value1, String value2) {
            addCriterion("zh_cn_content not between", value1, value2, "zhCnContent");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andStatusIsNull() {
            addCriterion("\"status\" is null");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andStatusIsNotNull() {
            addCriterion("\"status\" is not null");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andStatusEqualTo(Short value) {
            addCriterion("\"status\" =", value, "status");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andStatusNotEqualTo(Short value) {
            addCriterion("\"status\" <>", value, "status");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andStatusGreaterThan(String value) {
            addCriterion("\"status\" >", value, "status");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andStatusGreaterThanOrEqualTo(String value) {
            addCriterion("\"status\" >=", value, "status");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andStatusLessThan(String value) {
            addCriterion("\"status\" <", value, "status");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andStatusLessThanOrEqualTo(String value) {
            addCriterion("\"status\" <=", value, "status");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andStatusLike(String value) {
            addCriterion("\"status\" like", value, "status");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andStatusNotLike(String value) {
            addCriterion("\"status\" not like", value, "status");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andStatusIn(List<String> values) {
            addCriterion("\"status\" in", values, "status");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andStatusNotIn(List<String> values) {
            addCriterion("\"status\" not in", values, "status");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andStatusBetween(String value1, String value2) {
            addCriterion("\"status\" between", value1, value2, "status");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andStatusNotBetween(String value1, String value2) {
            addCriterion("\"status\" not between", value1, value2, "status");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andOrientdbIdIsNull() {
            addCriterion("orientdb_id is null");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andOrientdbIdIsNotNull() {
            addCriterion("orientdb_id is not null");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andOrientdbIdEqualTo(String value) {
            addCriterion("orientdb_id =", value, "orientdbId");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andOrientdbIdNotEqualTo(String value) {
            addCriterion("orientdb_id <>", value, "orientdbId");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andOrientdbIdGreaterThan(String value) {
            addCriterion("orientdb_id >", value, "orientdbId");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andOrientdbIdGreaterThanOrEqualTo(String value) {
            addCriterion("orientdb_id >=", value, "orientdbId");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andOrientdbIdLessThan(String value) {
            addCriterion("orientdb_id <", value, "orientdbId");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andOrientdbIdLessThanOrEqualTo(String value) {
            addCriterion("orientdb_id <=", value, "orientdbId");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andOrientdbIdLike(String value) {
            addCriterion("orientdb_id like", value, "orientdbId");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andOrientdbIdNotLike(String value) {
            addCriterion("orientdb_id not like", value, "orientdbId");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andOrientdbIdIn(List<String> values) {
            addCriterion("orientdb_id in", values, "orientdbId");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andOrientdbIdNotIn(List<String> values) {
            addCriterion("orientdb_id not in", values, "orientdbId");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andOrientdbIdBetween(String value1, String value2) {
            addCriterion("orientdb_id between", value1, value2, "orientdbId");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andOrientdbIdNotBetween(String value1, String value2) {
            addCriterion("orientdb_id not between", value1, value2, "orientdbId");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andCreatedByIsNull() {
            addCriterion("created_by is null");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andCreatedByIsNotNull() {
            addCriterion("created_by is not null");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andCreatedByEqualTo(Long value) {
            addCriterion("created_by =", value, "createdBy");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andCreatedByNotEqualTo(Long value) {
            addCriterion("created_by <>", value, "createdBy");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andCreatedByGreaterThan(Long value) {
            addCriterion("created_by >", value, "createdBy");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andCreatedByGreaterThanOrEqualTo(Long value) {
            addCriterion("created_by >=", value, "createdBy");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andCreatedByLessThan(Long value) {
            addCriterion("created_by <", value, "createdBy");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andCreatedByLessThanOrEqualTo(Long value) {
            addCriterion("created_by <=", value, "createdBy");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andCreatedByIn(List<Long> values) {
            addCriterion("created_by in", values, "createdBy");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andCreatedByNotIn(List<Long> values) {
            addCriterion("created_by not in", values, "createdBy");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andCreatedByBetween(Long value1, Long value2) {
            addCriterion("created_by between", value1, value2, "createdBy");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andCreatedByNotBetween(Long value1, Long value2) {
            addCriterion("created_by not between", value1, value2, "createdBy");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andCreatedNameByIsNull() {
            addCriterion("created_name_by is null");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andCreatedNameByIsNotNull() {
            addCriterion("created_name_by is not null");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andCreatedNameByEqualTo(String value) {
            addCriterion("created_name_by =", value, "createdNameBy");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andCreatedNameByNotEqualTo(String value) {
            addCriterion("created_name_by <>", value, "createdNameBy");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andCreatedNameByGreaterThan(String value) {
            addCriterion("created_name_by >", value, "createdNameBy");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andCreatedNameByGreaterThanOrEqualTo(String value) {
            addCriterion("created_name_by >=", value, "createdNameBy");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andCreatedNameByLessThan(String value) {
            addCriterion("created_name_by <", value, "createdNameBy");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andCreatedNameByLessThanOrEqualTo(String value) {
            addCriterion("created_name_by <=", value, "createdNameBy");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andCreatedNameByLike(String value) {
            addCriterion("created_name_by like", value, "createdNameBy");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andCreatedNameByNotLike(String value) {
            addCriterion("created_name_by not like", value, "createdNameBy");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andCreatedNameByIn(List<String> values) {
            addCriterion("created_name_by in", values, "createdNameBy");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andCreatedNameByNotIn(List<String> values) {
            addCriterion("created_name_by not in", values, "createdNameBy");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andCreatedNameByBetween(String value1, String value2) {
            addCriterion("created_name_by between", value1, value2, "createdNameBy");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andCreatedNameByNotBetween(String value1, String value2) {
            addCriterion("created_name_by not between", value1, value2, "createdNameBy");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andCreatedDateIsNull() {
            addCriterion("created_date is null");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andCreatedDateIsNotNull() {
            addCriterion("created_date is not null");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andCreatedDateEqualTo(Date value) {
            addCriterion("created_date =", value, "createdDate");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andCreatedDateNotEqualTo(Date value) {
            addCriterion("created_date <>", value, "createdDate");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andCreatedDateGreaterThan(Date value) {
            addCriterion("created_date >", value, "createdDate");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andCreatedDateGreaterThanOrEqualTo(Date value) {
            addCriterion("created_date >=", value, "createdDate");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andCreatedDateLessThan(Date value) {
            addCriterion("created_date <", value, "createdDate");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andCreatedDateLessThanOrEqualTo(Date value) {
            addCriterion("created_date <=", value, "createdDate");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andCreatedDateIn(List<Date> values) {
            addCriterion("created_date in", values, "createdDate");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andCreatedDateNotIn(List<Date> values) {
            addCriterion("created_date not in", values, "createdDate");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andCreatedDateBetween(Date value1, Date value2) {
            addCriterion("created_date between", value1, value2, "createdDate");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andCreatedDateNotBetween(Date value1, Date value2) {
            addCriterion("created_date not between", value1, value2, "createdDate");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andLastModifiedByIsNull() {
            addCriterion("last_modified_by is null");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andLastModifiedByIsNotNull() {
            addCriterion("last_modified_by is not null");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andLastModifiedByEqualTo(Long value) {
            addCriterion("last_modified_by =", value, "lastModifiedBy");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andLastModifiedByNotEqualTo(Long value) {
            addCriterion("last_modified_by <>", value, "lastModifiedBy");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andLastModifiedByGreaterThan(Long value) {
            addCriterion("last_modified_by >", value, "lastModifiedBy");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andLastModifiedByGreaterThanOrEqualTo(Long value) {
            addCriterion("last_modified_by >=", value, "lastModifiedBy");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andLastModifiedByLessThan(Long value) {
            addCriterion("last_modified_by <", value, "lastModifiedBy");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andLastModifiedByLessThanOrEqualTo(Long value) {
            addCriterion("last_modified_by <=", value, "lastModifiedBy");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andLastModifiedByIn(List<Long> values) {
            addCriterion("last_modified_by in", values, "lastModifiedBy");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andLastModifiedByNotIn(List<Long> values) {
            addCriterion("last_modified_by not in", values, "lastModifiedBy");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andLastModifiedByBetween(Long value1, Long value2) {
            addCriterion("last_modified_by between", value1, value2, "lastModifiedBy");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andLastModifiedByNotBetween(Long value1, Long value2) {
            addCriterion("last_modified_by not between", value1, value2, "lastModifiedBy");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andLastModifiedNameByIsNull() {
            addCriterion("last_modified_name_by is null");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andLastModifiedNameByIsNotNull() {
            addCriterion("last_modified_name_by is not null");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andLastModifiedNameByEqualTo(String value) {
            addCriterion("last_modified_name_by =", value, "lastModifiedNameBy");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andLastModifiedNameByNotEqualTo(String value) {
            addCriterion("last_modified_name_by <>", value, "lastModifiedNameBy");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andLastModifiedNameByGreaterThan(String value) {
            addCriterion("last_modified_name_by >", value, "lastModifiedNameBy");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andLastModifiedNameByGreaterThanOrEqualTo(String value) {
            addCriterion("last_modified_name_by >=", value, "lastModifiedNameBy");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andLastModifiedNameByLessThan(String value) {
            addCriterion("last_modified_name_by <", value, "lastModifiedNameBy");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andLastModifiedNameByLessThanOrEqualTo(String value) {
            addCriterion("last_modified_name_by <=", value, "lastModifiedNameBy");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andLastModifiedNameByLike(String value) {
            addCriterion("last_modified_name_by like", value, "lastModifiedNameBy");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andLastModifiedNameByNotLike(String value) {
            addCriterion("last_modified_name_by not like", value, "lastModifiedNameBy");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andLastModifiedNameByIn(List<String> values) {
            addCriterion("last_modified_name_by in", values, "lastModifiedNameBy");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andLastModifiedNameByNotIn(List<String> values) {
            addCriterion("last_modified_name_by not in", values, "lastModifiedNameBy");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andLastModifiedNameByBetween(String value1, String value2) {
            addCriterion("last_modified_name_by between", value1, value2, "lastModifiedNameBy");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andLastModifiedNameByNotBetween(String value1, String value2) {
            addCriterion("last_modified_name_by not between", value1, value2, "lastModifiedNameBy");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andLastModifiedDateIsNull() {
            addCriterion("last_modified_date is null");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andLastModifiedDateIsNotNull() {
            addCriterion("last_modified_date is not null");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andLastModifiedDateEqualTo(Date value) {
            addCriterion("last_modified_date =", value, "lastModifiedDate");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andLastModifiedDateNotEqualTo(Date value) {
            addCriterion("last_modified_date <>", value, "lastModifiedDate");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andLastModifiedDateGreaterThan(Date value) {
            addCriterion("last_modified_date >", value, "lastModifiedDate");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andLastModifiedDateGreaterThanOrEqualTo(Date value) {
            addCriterion("last_modified_date >=", value, "lastModifiedDate");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andLastModifiedDateLessThan(Date value) {
            addCriterion("last_modified_date <", value, "lastModifiedDate");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andLastModifiedDateLessThanOrEqualTo(Date value) {
            addCriterion("last_modified_date <=", value, "lastModifiedDate");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andLastModifiedDateIn(List<Date> values) {
            addCriterion("last_modified_date in", values, "lastModifiedDate");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andLastModifiedDateNotIn(List<Date> values) {
            addCriterion("last_modified_date not in", values, "lastModifiedDate");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andLastModifiedDateBetween(Date value1, Date value2) {
            addCriterion("last_modified_date between", value1, value2, "lastModifiedDate");
            return (JobExample.Criteria) this;
        }

        public JobExample.Criteria andLastModifiedDateNotBetween(Date value1, Date value2) {
            addCriterion("last_modified_date not between", value1, value2, "lastModifiedDate");
            return (JobExample.Criteria) this;
        }
    }

    /**
     */
    public static class Criteria extends JobExample.GeneratedCriteria {

        protected Criteria() {
            super();
        }
    }

    public static class Criterion {
        private String condition;

        private Object value;

        private Object secondValue;

        private boolean noValue;

        private boolean singleValue;

        private boolean betweenValue;

        private boolean listValue;

        private String typeHandler;

        public String getCondition() {
            return condition;
        }

        public Object getValue() {
            return value;
        }

        public Object getSecondValue() {
            return secondValue;
        }

        public boolean isNoValue() {
            return noValue;
        }

        public boolean isSingleValue() {
            return singleValue;
        }

        public boolean isBetweenValue() {
            return betweenValue;
        }

        public boolean isListValue() {
            return listValue;
        }

        public String getTypeHandler() {
            return typeHandler;
        }

        protected Criterion(String condition) {
            super();
            this.condition = condition;
            this.typeHandler = null;
            this.noValue = true;
        }

        protected Criterion(String condition, Object value, String typeHandler) {
            super();
            this.condition = condition;
            this.value = value;
            this.typeHandler = typeHandler;
            if (value instanceof List<?>) {
                this.listValue = true;
            } else {
                this.singleValue = true;
            }
        }

        protected Criterion(String condition, Object value) {
            this(condition, value, null);
        }

        protected Criterion(String condition, Object value, Object secondValue, String typeHandler) {
            super();
            this.condition = condition;
            this.value = value;
            this.secondValue = secondValue;
            this.typeHandler = typeHandler;
            this.betweenValue = true;
        }

        protected Criterion(String condition, Object value, Object secondValue) {
            this(condition, value, secondValue, null);
        }
    }
}
