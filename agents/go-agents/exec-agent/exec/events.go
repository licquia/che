//
// Copyright (c) 2012-2017 Codenvy, S.A.
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// which accompanies this distribution, and is available at
// http://www.eclipse.org/legal/epl-v10.html
//
// Contributors:
//   Codenvy, S.A. - initial API and implementation
//

package exec

import (
	"time"

	"github.com/eclipse/che/agents/go-agents/core/rpc"
)

// Types of process events
const (
	StartedEventType = "process_started"
	DiedEventType    = "process_died"
	StdoutEventType  = "process_stdout"
	StderrEventType  = "process_stderr"
)

// ProcessStatusEventBody is body of event that informs about process status
type ProcessStatusEventBody struct {
	rpc.Timed
	Pid         uint64 `json:"pid"`
	NativePid   int    `json:"nativePid"`
	Name        string `json:"name"`
	CommandLine string `json:"commandLine"`
}

// ProcessOutputEventBody is body of event that informs about process output
type ProcessOutputEventBody struct {
	rpc.Timed
	Pid  uint64 `json:"pid"`
	Text string `json:"text"`
}

func newStderrEvent(pid uint64, text string, when time.Time) *rpc.Event {
	return rpc.NewEvent(StderrEventType, &ProcessOutputEventBody{
		Timed: rpc.Timed{Time: when},
		Pid:   pid,
		Text:  text,
	})
}

func newStdoutEvent(pid uint64, text string, when time.Time) *rpc.Event {
	return rpc.NewEvent(StdoutEventType, &ProcessOutputEventBody{
		Timed: rpc.Timed{Time: when},
		Pid:   pid,
		Text:  text,
	})
}

func newStatusEvent(mp MachineProcess, status string) *rpc.Event {
	return rpc.NewEvent(status, &ProcessStatusEventBody{
		Timed:       rpc.Timed{Time: time.Now()},
		Pid:         mp.Pid,
		NativePid:   mp.NativePid,
		Name:        mp.Name,
		CommandLine: mp.CommandLine,
	})
}

func newStartedEvent(mp MachineProcess) *rpc.Event {
	return newStatusEvent(mp, StartedEventType)
}

func newDiedEvent(mp MachineProcess) *rpc.Event {
	return newStatusEvent(mp, DiedEventType)
}
