-- Rename legacy overlong case-management constraint and index names.
-- This keeps existing PostgreSQL databases aligned with the entity model.
-- The migration is safe to run more than once.

BEGIN;

ALTER INDEX IF EXISTS case_type_status_idx RENAME TO case_type_sts_idx;
ALTER INDEX IF EXISTS case_queue_member_party_idx RENAME TO case_qm_party_idx;
ALTER INDEX IF EXISTS case_stage_inst_date_idx RENAME TO case_stg_dt_idx;
ALTER INDEX IF EXISTS case_task_status_idx RENAME TO case_task_sts_idx;
ALTER INDEX IF EXISTS case_task_assignee_idx RENAME TO case_task_asg_idx;
ALTER INDEX IF EXISTS case_task_queue_idx RENAME TO case_task_q_idx;
ALTER INDEX IF EXISTS case_sla_inst_state_idx RENAME TO case_sla_state_idx;
ALTER INDEX IF EXISTS case_event_case_idx RENAME TO case_evt_case_idx;
ALTER INDEX IF EXISTS case_event_date_idx RENAME TO case_evt_date_idx;
ALTER INDEX IF EXISTS case_event_type_idx RENAME TO case_evt_type_idx;
ALTER INDEX IF EXISTS case_event_idem_idx RENAME TO case_evt_idem_idx;
ALTER INDEX IF EXISTS case_context_entity_idx RENAME TO case_ctx_ent_idx;
ALTER INDEX IF EXISTS case_var_expires_idx RENAME TO case_var_exp_idx;

-- Some older databases do not have every model index yet.
CREATE INDEX IF NOT EXISTS case_stg_dt_idx ON case_stage_instance (completed_date);
CREATE INDEX IF NOT EXISTS case_var_exp_idx ON case_variable (expires_at);
CREATE INDEX IF NOT EXISTS case_task_q_idx ON case_task (assigned_queue_id);
CREATE INDEX IF NOT EXISTS case_sla_state_idx ON case_sla_instance (sla_state_enum_id);
CREATE INDEX IF NOT EXISTS case_evt_type_idx ON case_event (event_type_enum_id);
CREATE INDEX IF NOT EXISTS case_ctx_ent_idx ON case_context (case_id, entity_name);

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_constraint
               WHERE conname = 'case_type_attr_type'
                 AND conrelid = to_regclass('public.case_type_attr')) THEN
        EXECUTE 'ALTER TABLE case_type_attr RENAME CONSTRAINT case_type_attr_type TO case_attr_type';
    END IF;
    IF EXISTS (SELECT 1 FROM pg_constraint
               WHERE conname = 'case_step_wait_type'
                 AND conrelid = to_regclass('public.case_step_wait')) THEN
        EXECUTE 'ALTER TABLE case_step_wait RENAME CONSTRAINT case_step_wait_type TO case_wait_type';
    END IF;
    IF EXISTS (SELECT 1 FROM pg_constraint
               WHERE conname = 'case_queue_member_queue'
                 AND conrelid = to_regclass('public.case_queue_member')) THEN
        EXECUTE 'ALTER TABLE case_queue_member RENAME CONSTRAINT case_queue_member_queue TO case_qm_queue';
    END IF;
    IF EXISTS (SELECT 1 FROM pg_constraint
               WHERE conname = 'case_queue_member_party'
                 AND conrelid = to_regclass('public.case_queue_member')) THEN
        EXECUTE 'ALTER TABLE case_queue_member RENAME CONSTRAINT case_queue_member_party TO case_qm_party';
    END IF;
    IF EXISTS (SELECT 1 FROM pg_constraint
               WHERE conname = 'case_fld_label_type'
                 AND conrelid = to_regclass('public.case_field_label')) THEN
        EXECUTE 'ALTER TABLE case_field_label RENAME CONSTRAINT case_fld_label_type TO case_label_type';
    END IF;
    IF EXISTS (SELECT 1 FROM pg_constraint
               WHERE conname = 'case_acc_rule_queue'
                 AND conrelid = to_regclass('public.case_access_rule')) THEN
        EXECUTE 'ALTER TABLE case_access_rule RENAME CONSTRAINT case_acc_rule_queue TO case_rule_queue';
    END IF;
    IF EXISTS (SELECT 1 FROM pg_constraint
               WHERE conname = 'case_stage_inst_case'
                 AND conrelid = to_regclass('public.case_stage_instance')) THEN
        EXECUTE 'ALTER TABLE case_stage_instance RENAME CONSTRAINT case_stage_inst_case TO case_stg_inst_case';
    END IF;
    IF EXISTS (SELECT 1 FROM pg_constraint
               WHERE conname = 'case_stage_inst_sts'
                 AND conrelid = to_regclass('public.case_stage_instance')) THEN
        EXECUTE 'ALTER TABLE case_stage_instance RENAME CONSTRAINT case_stage_inst_sts TO case_stg_inst_sts';
    END IF;
    IF EXISTS (SELECT 1 FROM pg_constraint
               WHERE conname = 'case_type'
                 AND conrelid = to_regclass('public."case"')) THEN
        EXECUTE 'ALTER TABLE "case" RENAME CONSTRAINT case_type TO case_to_type';
    ELSIF EXISTS (SELECT 1 FROM pg_constraint
                  WHERE conname = 'case_type'
                    AND conrelid = to_regclass('public.case_header')) THEN
        EXECUTE 'ALTER TABLE case_header RENAME CONSTRAINT case_type TO case_to_type';
    END IF;
    IF EXISTS (SELECT 1 FROM pg_constraint
               WHERE conname = 'case_start_form_type'
                 AND conrelid = to_regclass('public.case_type_start_form')) THEN
        EXECUTE 'ALTER TABLE case_type_start_form RENAME CONSTRAINT case_start_form_type TO case_start_form';
    END IF;
    IF EXISTS (SELECT 1 FROM pg_constraint
               WHERE conname = 'case_step_form_type'
                 AND conrelid = to_regclass('public.case_step_form')) THEN
        EXECUTE 'ALTER TABLE case_step_form RENAME CONSTRAINT case_step_form_type TO case_form_type';
    END IF;
END $$;

COMMIT;
