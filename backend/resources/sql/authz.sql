-- :name get-all-surveys-for-user :?
WITH
 PERMS AS (
        select distinct full_path from
                nodes n, user_node_role u, role_perms p
                WHERE u.user_id=:user-id
                      and u.node_id=n.id
                      and p.role=u.role_id
                      and p.perm ='PROJECT_FOLDER_READ')
select * from nodes n WHERE n.type = 'SURVEY' AND n.full_path <@ ARRAY(select * FROM PERMS)