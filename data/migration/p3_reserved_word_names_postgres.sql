-- Move reserved-word table/column names out of the physical schema.
-- Entity field names remain unchanged; only table/column names are mapped.
-- Safe to run more than once.

BEGIN;

DO $$
BEGIN
    IF to_regclass('public."case"') IS NOT NULL THEN
        IF EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_schema = 'public'
                     AND table_name = 'case'
                     AND column_name = 'version') THEN
            EXECUTE 'ALTER TABLE "case" RENAME COLUMN version TO case_version';
        END IF;
        IF to_regclass('public.case_header') IS NULL THEN
            EXECUTE 'ALTER TABLE "case" RENAME TO case_header';
        END IF;
    ELSIF to_regclass('public.case_header') IS NOT NULL THEN
        IF EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_schema = 'public'
                     AND table_name = 'case_header'
                     AND column_name = 'version') THEN
            EXECUTE 'ALTER TABLE case_header RENAME COLUMN version TO case_version';
        END IF;
    END IF;
END $$;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = 'public'
                 AND table_name = 'case_comment'
                 AND column_name = 'comment') THEN
        EXECUTE 'ALTER TABLE case_comment RENAME COLUMN comment TO comment_text';
    END IF;

    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = 'public'
                 AND table_name = 'case_field_mapping'
                 AND column_name = 'label') THEN
        EXECUTE 'ALTER TABLE case_field_mapping RENAME COLUMN label TO label_text';
    END IF;

    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = 'public'
                 AND table_name = 'case_field_label'
                 AND column_name = 'label') THEN
        EXECUTE 'ALTER TABLE case_field_label RENAME COLUMN label TO label_text';
    END IF;
END $$;

COMMIT;
