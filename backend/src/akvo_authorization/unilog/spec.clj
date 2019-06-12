(ns akvo-authorization.unilog.spec
  (:require [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen]))

(defn derive-all [h unilog-kind]
  (-> h
    (derive (keyword (str unilog-kind "Created")) (keyword unilog-kind))
    (derive (keyword (str unilog-kind "Updated")) (keyword unilog-kind))
    (derive (keyword (str unilog-kind "Deleted")) :delete)))

(def event-type-hierarchy (->
                            (make-hierarchy)
                            (derive-all "user")
                            (derive-all "userAuthorization")
                            (derive-all "userRole")
                            (derive-all "surveyGroup")))

(defmulti event-type
  (fn [event] (some-> event :eventType keyword))
  :hierarchy #'event-type-hierarchy)

(s/def ::id integer?)
(s/def ::name string?)

(s/def ::language string?)
(s/def ::userName string?)
(s/def ::emailAddress string?)
(s/def ::permissionList #{"20" "0" "10"})
(s/def ::superAdmin #{true false})

(defmethod event-type :user [_]
  (s/keys
    :req-un
    [::emailAddress ::id ::permissionList ::superAdmin]
    :opt-un
    [::language ::userName]))

(s/def ::roleId ::id)
(s/def ::userId ::id)
(s/def ::securedObjectId integer?)

(defmethod event-type :userAuthorization [_]
  (s/keys :req-un [::id ::roleId ::securedObjectId ::userId]))

(s/def ::permissions (s/coll-of #{"DATA_UPDATE" "FORM_READ" "PROJECT_FOLDER_CREATE" "DATA_DELETE"
                                  "DATA_CLEANING" "FORM_UPDATE" "CASCADE_MANAGE"
                                  "PROJECT_FOLDER_READ" "DATA_READ" "FORM_CREATE" "DEVICE_MANAGE"
                                  "DATA_APPROVE_MANAGE" "PROJECT_FOLDER_DELETE" "FORM_DELETE"
                                  "PROJECT_FOLDER_UPDATE"}))

(defmethod event-type :userRole [_]
  (s/keys :req-un [::id ::name ::permissions]))

(s/def ::parentId ::id)
(s/def ::public #{true false})
(s/def ::surveyGroupType #{"FOLDER" "SURVEY"})
(s/def ::description string?)

(defmethod event-type :surveyGroup [_]
  (s/keys
    :req-un
    [::id ::name ::public ::surveyGroupType]
    :opt-un
    [::description ::parentId]))

(defmethod event-type :delete [_]
  (s/keys :req-un [::id]))

(s/def ::entity (s/multi-spec event-type (fn [genv tag]
                                           (assoc genv
                                             :eventType
                                             (if (= :delete tag)
                                               (str (rand-nth ["user" "userAuthorization" "surveyGroup" "userRole"]) "Deleted")
                                               (str (name tag) (rand-nth ["Updated" "Created"])))))))

(s/def ::orgId string?)
(s/def ::payload (s/keys :req-un [::entity ::orgId]))
(s/def ::event (s/keys :req-un [::id ::payload]))

(comment
  (gen/sample (s/gen ::event))

  (s/unform ::event {:id 1
                      :payload {:orgId "h"
                                :entity {:id 12
                                         :language "x"
                                         :eventType "userCreated"
                                         :permissionList "10"
                                         :superAdmin true
                                         :emailAddress "vrgc7P6dbwB57F"}}}))