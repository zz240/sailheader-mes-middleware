package com.sailheader.middleware.model;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class ActionExample {
    protected String orderByClause;

    protected boolean distinct;

    protected List<ActionExample.Criteria> oredCriteria;

    private Integer limit;

    private Long offset;

    public ActionExample() {
        oredCriteria = new ArrayList<ActionExample.Criteria>();
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

    public List<ActionExample.Criteria> getOredCriteria() {
        return oredCriteria;
    }

    public void or(ActionExample.Criteria criteria) {
        oredCriteria.add(criteria);
    }

    public ActionExample.Criteria or() {
        ActionExample.Criteria criteria = createCriteriaInternal();
        oredCriteria.add(criteria);
        return criteria;
    }

    public ActionExample.Criteria createCriteria() {
        ActionExample.Criteria criteria = createCriteriaInternal();
        if (oredCriteria.size() == 0) {
            oredCriteria.add(criteria);
        }
        return criteria;
    }

    protected ActionExample.Criteria createCriteriaInternal() {
        ActionExample.Criteria criteria = new ActionExample.Criteria();
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
        protected List<ActionExample.Criterion> criteria;

        protected GeneratedCriteria() {
            super();
            criteria = new ArrayList<ActionExample.Criterion>();
        }

        public boolean isValid() {
            return criteria.size() > 0;
        }

        public List<ActionExample.Criterion> getAllCriteria() {
            return criteria;
        }

        public List<ActionExample.Criterion> getCriteria() {
            return criteria;
        }

        protected void addCriterion(String condition) {
            if (condition == null) {
                throw new RuntimeException("Value for condition cannot be null");
            }
            criteria.add(new ActionExample.Criterion(condition));
        }

        protected void addCriterion(String condition, Object value, String property) {
            if (value == null) {
                throw new RuntimeException("Value for " + property + " cannot be null");
            }
            criteria.add(new ActionExample.Criterion(condition, value));
        }

        protected void addCriterion(String condition, Object value1, Object value2, String property) {
            if (value1 == null || value2 == null) {
                throw new RuntimeException("Between values for " + property + " cannot be null");
            }
            criteria.add(new ActionExample.Criterion(condition, value1, value2));
        }

        public ActionExample.Criteria andIdIsNull() {
            addCriterion("id is null");
            return (ActionExample.Criteria) this;
        }

        public ActionExample.Criteria andIdIsNotNull() {
            addCriterion("id is not null");
            return (ActionExample.Criteria) this;
        }

        public ActionExample.Criteria andIdEqualTo(Long value) {
            addCriterion("id =", value, "id");
            return (ActionExample.Criteria) this;
        }

        public ActionExample.Criteria andIdNotEqualTo(Long value) {
            addCriterion("id <>", value, "id");
            return (ActionExample.Criteria) this;
        }

        public ActionExample.Criteria andIdGreaterThan(Long value) {
            addCriterion("id >", value, "id");
            return (ActionExample.Criteria) this;
        }

        public ActionExample.Criteria andIdGreaterThanOrEqualTo(Long value) {
            addCriterion("id >=", value, "id");
            return (ActionExample.Criteria) this;
        }

        public ActionExample.Criteria andIdLessThan(Long value) {
            addCriterion("id <", value, "id");
            return (ActionExample.Criteria) this;
        }

        public ActionExample.Criteria andIdLessThanOrEqualTo(Long value) {
            addCriterion("id <=", value, "id");
            return (ActionExample.Criteria) this;
        }

        public ActionExample.Criteria andIdIn(List<Long> values) {
            addCriterion("id in", values, "id");
            return (ActionExample.Criteria) this;
        }

        public ActionExample.Criteria andIdNotIn(List<Long> values) {
            addCriterion("id not in", values, "id");
            return (ActionExample.Criteria) this;
        }

        public ActionExample.Criteria andIdBetween(Long value1, Long value2) {
            addCriterion("id between", value1, value2, "id");
            return (ActionExample.Criteria) this;
        }

        public ActionExample.Criteria andIdNotBetween(Long value1, Long value2) {
            addCriterion("id not between", value1, value2, "id");
            return (ActionExample.Criteria) this;
        }

        public ActionExample.Criteria andTypeIsNull() {
            addCriterion("\"type\" is null");
            return (ActionExample.Criteria) this;
        }

        public ActionExample.Criteria andTypeIsNotNull() {
            addCriterion("\"type\" is not null");
            return (ActionExample.Criteria) this;
        }

        public ActionExample.Criteria andTypeEqualTo(String value) {
            addCriterion("\"type\" =", value, "type");
            return (ActionExample.Criteria) this;
        }

        public ActionExample.Criteria andTypeNotEqualTo(String value) {
            addCriterion("\"type\" <>", value, "type");
            return (ActionExample.Criteria) this;
        }

        public ActionExample.Criteria andTypeGreaterThan(String value) {
            addCriterion("\"type\" >", value, "type");
            return (ActionExample.Criteria) this;
        }

        public ActionExample.Criteria andTypeGreaterThanOrEqualTo(String value) {
            addCriterion("\"type\" >=", value, "type");
            return (ActionExample.Criteria) this;
        }

        public ActionExample.Criteria andTypeLessThan(String value) {
            addCriterion("\"type\" <", value, "type");
            return (ActionExample.Criteria) this;
        }

        public ActionExample.Criteria andTypeLessThanOrEqualTo(String value) {
            addCriterion("\"type\" <=", value, "type");
            return (ActionExample.Criteria) this;
        }

        public ActionExample.Criteria andTypeLike(String value) {
            addCriterion("\"type\" like", value, "type");
            return (ActionExample.Criteria) this;
        }

        public ActionExample.Criteria andTypeNotLike(String value) {
            addCriterion("\"type\" not like", value, "type");
            return (ActionExample.Criteria) this;
        }

        public ActionExample.Criteria andTypeIn(List<String> values) {
            addCriterion("\"type\" in", values, "type");
            return (ActionExample.Criteria) this;
        }

        public ActionExample.Criteria andTypeNotIn(List<String> values) {
            addCriterion("\"type\" not in", values, "type");
            return (ActionExample.Criteria) this;
        }

        public ActionExample.Criteria andTypeBetween(String value1, String value2) {
            addCriterion("\"type\" between", value1, value2, "type");
            return (ActionExample.Criteria) this;
        }

        public ActionExample.Criteria andTypeNotBetween(String value1, String value2) {
            addCriterion("\"type\" not between", value1, value2, "type");
            return (ActionExample.Criteria) this;
        }


        public ActionExample.Criteria andStatusIsNull() {
            addCriterion("\"status\" is null");
            return (ActionExample.Criteria) this;
        }

        public ActionExample.Criteria andStatusIsNotNull() {
            addCriterion("\"status\" is not null");
            return (ActionExample.Criteria) this;
        }

        public ActionExample.Criteria andStatusEqualTo(Short value) {
            addCriterion("\"status\" =", value, "status");
            return (ActionExample.Criteria) this;
        }

        public ActionExample.Criteria andStatusNotEqualTo(Short value) {
            addCriterion("\"status\" <>", value, "status");
            return (ActionExample.Criteria) this;
        }

        public ActionExample.Criteria andStatusGreaterThan(String value) {
            addCriterion("\"status\" >", value, "status");
            return (ActionExample.Criteria) this;
        }

        public ActionExample.Criteria andStatusGreaterThanOrEqualTo(String value) {
            addCriterion("\"status\" >=", value, "status");
            return (ActionExample.Criteria) this;
        }

        public ActionExample.Criteria andStatusLessThan(String value) {
            addCriterion("\"status\" <", value, "status");
            return (ActionExample.Criteria) this;
        }

        public ActionExample.Criteria andStatusLessThanOrEqualTo(String value) {
            addCriterion("\"status\" <=", value, "status");
            return (ActionExample.Criteria) this;
        }

        public ActionExample.Criteria andStatusLike(String value) {
            addCriterion("\"status\" like", value, "status");
            return (ActionExample.Criteria) this;
        }

        public ActionExample.Criteria andStatusNotLike(String value) {
            addCriterion("\"status\" not like", value, "status");
            return (ActionExample.Criteria) this;
        }

        public ActionExample.Criteria andStatusIn(List<String> values) {
            addCriterion("\"status\" in", values, "status");
            return (ActionExample.Criteria) this;
        }

        public ActionExample.Criteria andStatusNotIn(List<String> values) {
            addCriterion("\"status\" not in", values, "status");
            return (ActionExample.Criteria) this;
        }

        public ActionExample.Criteria andStatusBetween(String value1, String value2) {
            addCriterion("\"status\" between", value1, value2, "status");
            return (ActionExample.Criteria) this;
        }

        public ActionExample.Criteria andStatusNotBetween(String value1, String value2) {
            addCriterion("\"status\" not between", value1, value2, "status");
            return (ActionExample.Criteria) this;
        }


        public ActionExample.Criteria andCreatedByIsNull() {
            addCriterion("created_by is null");
            return (ActionExample.Criteria) this;
        }

        public ActionExample.Criteria andCreatedByIsNotNull() {
            addCriterion("created_by is not null");
            return (ActionExample.Criteria) this;
        }

        public ActionExample.Criteria andCreatedByEqualTo(Long value) {
            addCriterion("created_by =", value, "createdBy");
            return (ActionExample.Criteria) this;
        }

        public ActionExample.Criteria andCreatedByNotEqualTo(Long value) {
            addCriterion("created_by <>", value, "createdBy");
            return (ActionExample.Criteria) this;
        }

        public ActionExample.Criteria andCreatedByGreaterThan(Long value) {
            addCriterion("created_by >", value, "createdBy");
            return (ActionExample.Criteria) this;
        }

        public ActionExample.Criteria andCreatedByGreaterThanOrEqualTo(Long value) {
            addCriterion("created_by >=", value, "createdBy");
            return (ActionExample.Criteria) this;
        }

        public ActionExample.Criteria andCreatedByLessThan(Long value) {
            addCriterion("created_by <", value, "createdBy");
            return (ActionExample.Criteria) this;
        }

        public ActionExample.Criteria andCreatedByLessThanOrEqualTo(Long value) {
            addCriterion("created_by <=", value, "createdBy");
            return (ActionExample.Criteria) this;
        }

        public ActionExample.Criteria andCreatedByIn(List<Long> values) {
            addCriterion("created_by in", values, "createdBy");
            return (ActionExample.Criteria) this;
        }

        public ActionExample.Criteria andCreatedByNotIn(List<Long> values) {
            addCriterion("created_by not in", values, "createdBy");
            return (ActionExample.Criteria) this;
        }

        public ActionExample.Criteria andCreatedByBetween(Long value1, Long value2) {
            addCriterion("created_by between", value1, value2, "createdBy");
            return (ActionExample.Criteria) this;
        }

        public ActionExample.Criteria andCreatedByNotBetween(Long value1, Long value2) {
            addCriterion("created_by not between", value1, value2, "createdBy");
            return (ActionExample.Criteria) this;
        }

        public ActionExample.Criteria andCreatedNameByIsNull() {
            addCriterion("created_name_by is null");
            return (ActionExample.Criteria) this;
        }

        public ActionExample.Criteria andCreatedNameByIsNotNull() {
            addCriterion("created_name_by is not null");
            return (ActionExample.Criteria) this;
        }

        public ActionExample.Criteria andCreatedNameByEqualTo(String value) {
            addCriterion("created_name_by =", value, "createdNameBy");
            return (ActionExample.Criteria) this;
        }

        public ActionExample.Criteria andCreatedNameByNotEqualTo(String value) {
            addCriterion("created_name_by <>", value, "createdNameBy");
            return (ActionExample.Criteria) this;
        }

        public ActionExample.Criteria andCreatedNameByGreaterThan(String value) {
            addCriterion("created_name_by >", value, "createdNameBy");
            return (ActionExample.Criteria) this;
        }

        public ActionExample.Criteria andCreatedNameByGreaterThanOrEqualTo(String value) {
            addCriterion("created_name_by >=", value, "createdNameBy");
            return (ActionExample.Criteria) this;
        }

        public ActionExample.Criteria andCreatedNameByLessThan(String value) {
            addCriterion("created_name_by <", value, "createdNameBy");
            return (ActionExample.Criteria) this;
        }

        public ActionExample.Criteria andCreatedNameByLessThanOrEqualTo(String value) {
            addCriterion("created_name_by <=", value, "createdNameBy");
            return (ActionExample.Criteria) this;
        }

        public ActionExample.Criteria andCreatedNameByLike(String value) {
            addCriterion("created_name_by like", value, "createdNameBy");
            return (ActionExample.Criteria) this;
        }

        public ActionExample.Criteria andCreatedNameByNotLike(String value) {
            addCriterion("created_name_by not like", value, "createdNameBy");
            return (ActionExample.Criteria) this;
        }

        public ActionExample.Criteria andCreatedNameByIn(List<String> values) {
            addCriterion("created_name_by in", values, "createdNameBy");
            return (ActionExample.Criteria) this;
        }

        public ActionExample.Criteria andCreatedNameByNotIn(List<String> values) {
            addCriterion("created_name_by not in", values, "createdNameBy");
            return (ActionExample.Criteria) this;
        }

        public ActionExample.Criteria andCreatedNameByBetween(String value1, String value2) {
            addCriterion("created_name_by between", value1, value2, "createdNameBy");
            return (ActionExample.Criteria) this;
        }

        public ActionExample.Criteria andCreatedNameByNotBetween(String value1, String value2) {
            addCriterion("created_name_by not between", value1, value2, "createdNameBy");
            return (ActionExample.Criteria) this;
        }

        public ActionExample.Criteria andCreatedDateIsNull() {
            addCriterion("created_date is null");
            return (ActionExample.Criteria) this;
        }

        public ActionExample.Criteria andCreatedDateIsNotNull() {
            addCriterion("created_date is not null");
            return (ActionExample.Criteria) this;
        }

        public ActionExample.Criteria andCreatedDateEqualTo(Date value) {
            addCriterion("created_date =", value, "createdDate");
            return (ActionExample.Criteria) this;
        }

        public ActionExample.Criteria andCreatedDateNotEqualTo(Date value) {
            addCriterion("created_date <>", value, "createdDate");
            return (ActionExample.Criteria) this;
        }

        public ActionExample.Criteria andCreatedDateGreaterThan(Date value) {
            addCriterion("created_date >", value, "createdDate");
            return (ActionExample.Criteria) this;
        }

        public ActionExample.Criteria andCreatedDateGreaterThanOrEqualTo(Date value) {
            addCriterion("created_date >=", value, "createdDate");
            return (ActionExample.Criteria) this;
        }

        public ActionExample.Criteria andCreatedDateLessThan(Date value) {
            addCriterion("created_date <", value, "createdDate");
            return (ActionExample.Criteria) this;
        }

        public ActionExample.Criteria andCreatedDateLessThanOrEqualTo(Date value) {
            addCriterion("created_date <=", value, "createdDate");
            return (ActionExample.Criteria) this;
        }

        public ActionExample.Criteria andCreatedDateIn(List<Date> values) {
            addCriterion("created_date in", values, "createdDate");
            return (ActionExample.Criteria) this;
        }

        public ActionExample.Criteria andCreatedDateNotIn(List<Date> values) {
            addCriterion("created_date not in", values, "createdDate");
            return (ActionExample.Criteria) this;
        }

        public ActionExample.Criteria andCreatedDateBetween(Date value1, Date value2) {
            addCriterion("created_date between", value1, value2, "createdDate");
            return (ActionExample.Criteria) this;
        }

        public ActionExample.Criteria andCreatedDateNotBetween(Date value1, Date value2) {
            addCriterion("created_date not between", value1, value2, "createdDate");
            return (ActionExample.Criteria) this;
        }

        public ActionExample.Criteria andLastModifiedByIsNull() {
            addCriterion("last_modified_by is null");
            return (ActionExample.Criteria) this;
        }

        public ActionExample.Criteria andLastModifiedByIsNotNull() {
            addCriterion("last_modified_by is not null");
            return (ActionExample.Criteria) this;
        }

        public ActionExample.Criteria andLastModifiedByEqualTo(Long value) {
            addCriterion("last_modified_by =", value, "lastModifiedBy");
            return (ActionExample.Criteria) this;
        }

        public ActionExample.Criteria andLastModifiedByNotEqualTo(Long value) {
            addCriterion("last_modified_by <>", value, "lastModifiedBy");
            return (ActionExample.Criteria) this;
        }

        public ActionExample.Criteria andLastModifiedByGreaterThan(Long value) {
            addCriterion("last_modified_by >", value, "lastModifiedBy");
            return (ActionExample.Criteria) this;
        }

        public ActionExample.Criteria andLastModifiedByGreaterThanOrEqualTo(Long value) {
            addCriterion("last_modified_by >=", value, "lastModifiedBy");
            return (ActionExample.Criteria) this;
        }

        public ActionExample.Criteria andLastModifiedByLessThan(Long value) {
            addCriterion("last_modified_by <", value, "lastModifiedBy");
            return (ActionExample.Criteria) this;
        }

        public ActionExample.Criteria andLastModifiedByLessThanOrEqualTo(Long value) {
            addCriterion("last_modified_by <=", value, "lastModifiedBy");
            return (ActionExample.Criteria) this;
        }

        public ActionExample.Criteria andLastModifiedByIn(List<Long> values) {
            addCriterion("last_modified_by in", values, "lastModifiedBy");
            return (ActionExample.Criteria) this;
        }

        public ActionExample.Criteria andLastModifiedByNotIn(List<Long> values) {
            addCriterion("last_modified_by not in", values, "lastModifiedBy");
            return (ActionExample.Criteria) this;
        }

        public ActionExample.Criteria andLastModifiedByBetween(Long value1, Long value2) {
            addCriterion("last_modified_by between", value1, value2, "lastModifiedBy");
            return (ActionExample.Criteria) this;
        }

        public ActionExample.Criteria andLastModifiedByNotBetween(Long value1, Long value2) {
            addCriterion("last_modified_by not between", value1, value2, "lastModifiedBy");
            return (ActionExample.Criteria) this;
        }

        public ActionExample.Criteria andLastModifiedNameByIsNull() {
            addCriterion("last_modified_name_by is null");
            return (ActionExample.Criteria) this;
        }

        public ActionExample.Criteria andLastModifiedNameByIsNotNull() {
            addCriterion("last_modified_name_by is not null");
            return (ActionExample.Criteria) this;
        }

        public ActionExample.Criteria andLastModifiedNameByEqualTo(String value) {
            addCriterion("last_modified_name_by =", value, "lastModifiedNameBy");
            return (ActionExample.Criteria) this;
        }

        public ActionExample.Criteria andLastModifiedNameByNotEqualTo(String value) {
            addCriterion("last_modified_name_by <>", value, "lastModifiedNameBy");
            return (ActionExample.Criteria) this;
        }

        public ActionExample.Criteria andLastModifiedNameByGreaterThan(String value) {
            addCriterion("last_modified_name_by >", value, "lastModifiedNameBy");
            return (ActionExample.Criteria) this;
        }

        public ActionExample.Criteria andLastModifiedNameByGreaterThanOrEqualTo(String value) {
            addCriterion("last_modified_name_by >=", value, "lastModifiedNameBy");
            return (ActionExample.Criteria) this;
        }

        public ActionExample.Criteria andLastModifiedNameByLessThan(String value) {
            addCriterion("last_modified_name_by <", value, "lastModifiedNameBy");
            return (ActionExample.Criteria) this;
        }

        public ActionExample.Criteria andLastModifiedNameByLessThanOrEqualTo(String value) {
            addCriterion("last_modified_name_by <=", value, "lastModifiedNameBy");
            return (ActionExample.Criteria) this;
        }

        public ActionExample.Criteria andLastModifiedNameByLike(String value) {
            addCriterion("last_modified_name_by like", value, "lastModifiedNameBy");
            return (ActionExample.Criteria) this;
        }

        public ActionExample.Criteria andLastModifiedNameByNotLike(String value) {
            addCriterion("last_modified_name_by not like", value, "lastModifiedNameBy");
            return (ActionExample.Criteria) this;
        }

        public ActionExample.Criteria andLastModifiedNameByIn(List<String> values) {
            addCriterion("last_modified_name_by in", values, "lastModifiedNameBy");
            return (ActionExample.Criteria) this;
        }

        public ActionExample.Criteria andLastModifiedNameByNotIn(List<String> values) {
            addCriterion("last_modified_name_by not in", values, "lastModifiedNameBy");
            return (ActionExample.Criteria) this;
        }

        public ActionExample.Criteria andLastModifiedNameByBetween(String value1, String value2) {
            addCriterion("last_modified_name_by between", value1, value2, "lastModifiedNameBy");
            return (ActionExample.Criteria) this;
        }

        public ActionExample.Criteria andLastModifiedNameByNotBetween(String value1, String value2) {
            addCriterion("last_modified_name_by not between", value1, value2, "lastModifiedNameBy");
            return (ActionExample.Criteria) this;
        }

        public ActionExample.Criteria andLastModifiedDateIsNull() {
            addCriterion("last_modified_date is null");
            return (ActionExample.Criteria) this;
        }

        public ActionExample.Criteria andLastModifiedDateIsNotNull() {
            addCriterion("last_modified_date is not null");
            return (ActionExample.Criteria) this;
        }

        public ActionExample.Criteria andLastModifiedDateEqualTo(Date value) {
            addCriterion("last_modified_date =", value, "lastModifiedDate");
            return (ActionExample.Criteria) this;
        }

        public ActionExample.Criteria andLastModifiedDateNotEqualTo(Date value) {
            addCriterion("last_modified_date <>", value, "lastModifiedDate");
            return (ActionExample.Criteria) this;
        }

        public ActionExample.Criteria andLastModifiedDateGreaterThan(Date value) {
            addCriterion("last_modified_date >", value, "lastModifiedDate");
            return (ActionExample.Criteria) this;
        }

        public ActionExample.Criteria andLastModifiedDateGreaterThanOrEqualTo(Date value) {
            addCriterion("last_modified_date >=", value, "lastModifiedDate");
            return (ActionExample.Criteria) this;
        }

        public ActionExample.Criteria andLastModifiedDateLessThan(Date value) {
            addCriterion("last_modified_date <", value, "lastModifiedDate");
            return (ActionExample.Criteria) this;
        }

        public ActionExample.Criteria andLastModifiedDateLessThanOrEqualTo(Date value) {
            addCriterion("last_modified_date <=", value, "lastModifiedDate");
            return (ActionExample.Criteria) this;
        }

        public ActionExample.Criteria andLastModifiedDateIn(List<Date> values) {
            addCriterion("last_modified_date in", values, "lastModifiedDate");
            return (ActionExample.Criteria) this;
        }

        public ActionExample.Criteria andLastModifiedDateNotIn(List<Date> values) {
            addCriterion("last_modified_date not in", values, "lastModifiedDate");
            return (ActionExample.Criteria) this;
        }

        public ActionExample.Criteria andLastModifiedDateBetween(Date value1, Date value2) {
            addCriterion("last_modified_date between", value1, value2, "lastModifiedDate");
            return (ActionExample.Criteria) this;
        }

        public ActionExample.Criteria andLastModifiedDateNotBetween(Date value1, Date value2) {
            addCriterion("last_modified_date not between", value1, value2, "lastModifiedDate");
            return (ActionExample.Criteria) this;
        }
    }

    /**
     */
    public static class Criteria extends ActionExample.GeneratedCriteria {

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
