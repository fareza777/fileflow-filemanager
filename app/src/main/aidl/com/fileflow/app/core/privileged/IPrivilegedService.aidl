// The interface Shizuku binds to: the service below runs in a separate process
// with the shell's uid, so a command it runs is a command the shell could run.
//
// `destroy` must keep the transaction id Shizuku assigns it, which is how the
// manager asks a non-daemon service to shut down; the constant is part of the
// protocol rather than a style choice.
package com.fileflow.app.core.privileged;

interface IPrivilegedService {
    void destroy() = 16777114;
    String runCommand(String command, int timeoutSeconds) = 1;
}
