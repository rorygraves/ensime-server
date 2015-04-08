package org.ensime.server

import org.ensime.core.EnsimeEvent

trait EventServer {

  /**
   * Subscribe to async events from the project, replaying previously seen events if requested.
   * The first subscriber will get all undelivered events (subsequent subscribers do not).
   * @param handler The callback handler for events
   * @return True if caller is first subscriber, False otherwise
   */
  def subscribeToEvents(handler: EnsimeEvent => Unit): Boolean
}