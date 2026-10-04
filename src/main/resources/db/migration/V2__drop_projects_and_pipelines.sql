-- V1 keeps only the analysis history: no users, projects, members or pipelines.
-- An analysis is identified by its Jenkins build URL / job path alone.

ALTER TABLE analyses DROP COLUMN pipeline_id; -- also drops idx_analyses_pipeline and the foreign key

DROP TABLE pipelines;
DROP TABLE project_members;
DROP TABLE projects;
DROP TABLE users;
