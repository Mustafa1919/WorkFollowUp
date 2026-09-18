-- V2'deki politikalar current_setting('app.current_workspace_id', true)::uuid kullaniyordu.
-- Custom (bilinmeyen sinifta) bir GUC, bir connection'da EN AZ BIR KEZ set_config ile
-- set edildikten sonra, sonraki transaction'larda context hic set edilmese bile reset
-- degeri NULL degil BOS STRING'tir (Postgres custom placeholder GUC davranisi). Bu da
-- ''::uuid cast'inde sert bir SQL hatasina yol acar — context'siz erisimde beklenen
-- "sessizce sifir satir" (fail-closed) davranisi yerine 500 firlatir. NULLIF ile bos
-- string'i cast'ten once NULL'a ceviriyoruz; workspace_id = NULL karsilastirmasi UNKNOWN
-- olur ve satir guvenli sekilde gizlenir.

DROP POLICY tenant_isolation ON projects;
CREATE POLICY tenant_isolation ON projects
    USING (workspace_id = NULLIF(current_setting('app.current_workspace_id', true), '')::uuid)
    WITH CHECK (workspace_id = NULLIF(current_setting('app.current_workspace_id', true), '')::uuid);

DROP POLICY tenant_isolation ON sprints;
CREATE POLICY tenant_isolation ON sprints
    USING (workspace_id = NULLIF(current_setting('app.current_workspace_id', true), '')::uuid)
    WITH CHECK (workspace_id = NULLIF(current_setting('app.current_workspace_id', true), '')::uuid);

DROP POLICY tenant_isolation ON tasks;
CREATE POLICY tenant_isolation ON tasks
    USING (workspace_id = NULLIF(current_setting('app.current_workspace_id', true), '')::uuid)
    WITH CHECK (workspace_id = NULLIF(current_setting('app.current_workspace_id', true), '')::uuid);
