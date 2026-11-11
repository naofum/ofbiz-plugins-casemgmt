-- Add the composite primary keys that the entity model declares.
--
-- CaseContextKey, CaseStepForm and CaseTypeStartForm were created before their
-- composite keys were defined. OFBiz added the missing columns on startup but
-- does not change a primary key automatically, so the database kept no primary
-- key. Run this script with OFBiz stopped and after a backup. It is safe to run
-- more than once: a table that already has a primary key is skipped.

BEGIN;

DO $$
BEGIN
    IF to_regclass('public.case_context_key') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint
                       WHERE contype = 'p'
                         AND conrelid = to_regclass('public.case_context_key')) THEN
        ALTER TABLE case_context_key
            ADD CONSTRAINT pk_case_context_key
            PRIMARY KEY (case_id, context_seq, key_seq);
        RAISE NOTICE 'added primary key pk_case_context_key';
    END IF;

    IF to_regclass('public.case_step_form') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint
                       WHERE contype = 'p'
                         AND conrelid = to_regclass('public.case_step_form')) THEN
        ALTER TABLE case_step_form
            ADD CONSTRAINT pk_case_step_form
            PRIMARY KEY (case_type_id, definition_version, stage_seq, step_seq);
        RAISE NOTICE 'added primary key pk_case_step_form';
    END IF;

    IF to_regclass('public.case_type_start_form') IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM pg_constraint
                       WHERE contype = 'p'
                         AND conrelid = to_regclass('public.case_type_start_form')) THEN
        ALTER TABLE case_type_start_form
            ADD CONSTRAINT pk_case_type_start_form
            PRIMARY KEY (case_type_id, definition_version);
        RAISE NOTICE 'added primary key pk_case_type_start_form';
    END IF;
END $$;

COMMIT;
