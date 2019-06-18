-- :name get-all-surveys-for-user :?
WITH
 PERMS AS (
        select distinct full_path from
                nodes n, user_node_role u
                WHERE u.user_id=:user-id
                      and u.node_id=n.id
        UNION -- super admin case
        select full_path from
            nodes n, users_flow_ids ufi
            where n.flow_instance = ufi.flow_instance
                and ufi.user_id = :user-id
                and ufi.super_admin = true
                and n.flow_id = 0)
select flow_id, flow_instance from nodes n WHERE n.type = 'SURVEY' AND n.full_path <@ ARRAY(select * FROM PERMS)