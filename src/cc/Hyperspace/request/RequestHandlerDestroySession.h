/*
 * Copyright (C) 2007-2016 Hypertable, Inc.
 *
 * This file is part of Hypertable.
 *
 * Hypertable is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 3
 * of the License, or any later version.
 *
 * Hypertable is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA
 * 02110-1301, USA.
 */

#ifndef Hyperspace_request_RequestHandlerDestroySession_h
#define Hyperspace_request_RequestHandlerDestroySession_h

#include "AsyncComm/ApplicationHandler.h"
#include "AsyncComm/Comm.h"

namespace Hyperspace {
  using namespace Hypertable;
  class Master;

  class RequestHandlerDestroySession : public ApplicationHandler {
  public:
    RequestHandlerDestroySession(Master *master, uint64_t session_id)
      : m_master(master), m_session_id(session_id) { }
    virtual ~RequestHandlerDestroySession() { }

    virtual void run();

  private:
    Master   *m_master;
    uint64_t  m_session_id;
  };
}

#endif // Hyperspace_request_RequestHandlerDestroySession_h
