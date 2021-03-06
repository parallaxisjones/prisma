package com.prisma.api.database.mutactions.mutactions

import java.sql.SQLException

import com.prisma.api.mutations.mutations.CascadingDeletes.Path
import com.prisma.api.database.DatabaseMutationBuilder._
import com.prisma.api.database.mutactions.{ClientSqlDataChangeMutaction, ClientSqlStatementResult}
import com.prisma.api.schema.APIErrors.RequiredRelationWouldBeViolated
import com.prisma.shared.models.{Field, Project, Relation}
import com.prisma.util.gc_value.OtherGCStuff.parameterStringFromSQLException
import slick.dbio.DBIOAction

import scala.concurrent.Future

case class CascadingDeleteRelationMutactions(project: Project, path: Path) extends ClientSqlDataChangeMutaction {

  val relationFieldsNotOnPath      = path.lastModel.relationFields.filter(f => !path.edges.map(_.relation).contains(f.relation.get))
  val relationsWhereThisIsRequired = relationFieldsNotOnPath.filter(otherSideIsRequired).map(_.relation.get)
  val requiredCheck                = relationsWhereThisIsRequired.map(relation => oldParentFailureTriggerByPath(project, relation, path))

  val deleteAction = List(cascadingDeleteChildActions(project.id, path))

  override def execute = {
    val allActions = requiredCheck ++ deleteAction
    Future.successful(ClientSqlStatementResult(DBIOAction.seq(allActions: _*)))
  }

  override def handleErrors = {
    Some({
      case e: SQLException if e.getErrorCode == 1242 && otherFailingRequiredRelationOnChild(e.getCause.toString).isDefined =>
        throw RequiredRelationWouldBeViolated(project, otherFailingRequiredRelationOnChild(e.getCause.toString).get)
    })
  }

  def otherFailingRequiredRelationOnChild(cause: String): Option[Relation] = relationsWhereThisIsRequired.collectFirst {
    case x if causedByThisMutactionChildOnly(x, cause) => x
  }

  def causedByThisMutactionChildOnly(relation: Relation, cause: String) = {
    val parentCheckString = s"`${relation.id}` OLDPARENTPATHFAILURETRIGGER WHERE `${relation.sideOf(path.lastModel)}`"
    cause.contains(parentCheckString) && cause.contains(parameterStringFromSQLException(path.where))
  }

  def otherSideIsRequired(field: Field): Boolean = field.relatedField(project.schema) match {
    case Some(f) if f.isRequired => true
    case Some(_)                 => false
    case None                    => false
  }
}
