-- :name get-all-surveys-for-user :?
WITH
 PERMS AS (
        select distinct full_path from
                nodes n, user_node_role u
                WHERE u.user_id=:user-id
                      and u.node_id=n.id)
select * from nodes n WHERE n.type = 'SURVEY' AND n.full_path <@ ARRAY(select * FROM PERMS)