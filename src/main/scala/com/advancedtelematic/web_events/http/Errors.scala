package com.advancedtelematic.web_events.http

import akka.http.scaladsl.model.StatusCodes
import com.advancedtelematic.libats.data.ErrorCode
import com.advancedtelematic.libats.http.Errors.RawError

object Errors {
  def scopeLacksNamespace(scope: Set[String]): RawError = RawError(
    ErrorCode("missing_namespace_in_token_scope"),
    StatusCodes.Unauthorized,
    s"The scope of the token does not provide a namespace. Scope: $scope."
  )
}
