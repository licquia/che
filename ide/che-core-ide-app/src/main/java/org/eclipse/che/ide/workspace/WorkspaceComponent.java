/*******************************************************************************
 * Copyright (c) 2012-2017 Codenvy, S.A.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *   Codenvy, S.A. - initial API and implementation
 *******************************************************************************/
package org.eclipse.che.ide.workspace;

import com.google.gwt.core.client.Callback;
import com.google.gwt.core.client.Scheduler;
import com.google.web.bindery.event.shared.EventBus;

import org.eclipse.che.api.core.model.workspace.Workspace;
import org.eclipse.che.api.core.model.workspace.WorkspaceStatus;
import org.eclipse.che.api.promises.client.Function;
import org.eclipse.che.api.promises.client.FunctionException;
import org.eclipse.che.api.promises.client.Operation;
import org.eclipse.che.api.promises.client.OperationException;
import org.eclipse.che.api.promises.client.PromiseError;
import org.eclipse.che.api.workspace.shared.dto.WorkspaceDto;
import org.eclipse.che.ide.CoreLocalizationConstant;
import org.eclipse.che.ide.api.app.AppContext;
import org.eclipse.che.ide.api.component.Component;
import org.eclipse.che.ide.api.dialogs.DialogFactory;
import org.eclipse.che.ide.api.machine.events.WsAgentStateEvent;
import org.eclipse.che.ide.api.machine.events.WsAgentStateHandler;
import org.eclipse.che.ide.api.notification.NotificationManager;
import org.eclipse.che.ide.api.notification.StatusNotification;
import org.eclipse.che.ide.api.preferences.PreferencesManager;
import org.eclipse.che.ide.api.workspace.WorkspaceServiceClient;
import org.eclipse.che.ide.api.workspace.event.WorkspaceStartedEvent;
import org.eclipse.che.ide.api.workspace.event.WorkspaceStartingEvent;
import org.eclipse.che.ide.api.workspace.event.WorkspaceStoppedEvent;
import org.eclipse.che.ide.context.BrowserAddress;
import org.eclipse.che.ide.dto.DtoFactory;
import org.eclipse.che.ide.rest.DtoUnmarshallerFactory;
import org.eclipse.che.ide.ui.loaders.LoaderPresenter;
import org.eclipse.che.ide.websocket.MessageBus;
import org.eclipse.che.ide.websocket.MessageBusProvider;
import org.eclipse.che.ide.websocket.events.ConnectionOpenedHandler;
import org.eclipse.che.ide.workspace.create.CreateWorkspacePresenter;
import org.eclipse.che.ide.workspace.start.StartWorkspacePresenter;

import java.util.Map;

import static org.eclipse.che.api.workspace.shared.Constants.CHE_WORKSPACE_AUTO_START;
import static org.eclipse.che.ide.api.notification.StatusNotification.DisplayMode.FLOAT_MODE;
import static org.eclipse.che.ide.ui.loaders.LoaderPresenter.Phase.CREATING_WORKSPACE_SNAPSHOT;
import static org.eclipse.che.ide.ui.loaders.LoaderPresenter.Phase.STARTING_WORKSPACE_RUNTIME;
import static org.eclipse.che.ide.ui.loaders.LoaderPresenter.Phase.WORKSPACE_STOPPED;

/**
 * @author Evgen Vidolob
 * @author Dmitry Shnurenko
 * @author Yevhenii Voevodin
 */
public abstract class WorkspaceComponent implements Component, WsAgentStateHandler, WorkspaceStoppedEvent.Handler {

    protected final WorkspaceServiceClient   workspaceServiceClient;
    protected final CoreLocalizationConstant locale;
    protected final CreateWorkspacePresenter createWorkspacePresenter;
    protected final DtoUnmarshallerFactory   dtoUnmarshallerFactory;
    protected final AppContext               appContext;
    protected final BrowserAddress           browserAddress;
    protected final DialogFactory            dialogFactory;
    protected final PreferencesManager       preferencesManager;
    protected final DtoFactory               dtoFactory;
    protected final NotificationManager      notificationManager;
    protected final StartWorkspacePresenter  startWorkspacePresenter;

    private final EventBus                 eventBus;
    private final MessageBusProvider       messageBusProvider;
    private final WorkspaceEventsHandler   workspaceEventsHandler;
    private final LoaderPresenter          loader;

    protected Callback<Component, Exception> callback;
    protected boolean                        needToReloadComponents;
    private   MessageBus                     messageBus;

    public WorkspaceComponent(WorkspaceServiceClient workspaceServiceClient,
                              CreateWorkspacePresenter createWorkspacePresenter,
                              StartWorkspacePresenter startWorkspacePresenter,
                              CoreLocalizationConstant locale,
                              DtoUnmarshallerFactory dtoUnmarshallerFactory,
                              EventBus eventBus,
                              AppContext appContext,
                              NotificationManager notificationManager,
                              MessageBusProvider messageBusProvider,
                              BrowserAddress browserAddress,
                              DialogFactory dialogFactory,
                              PreferencesManager preferencesManager,
                              DtoFactory dtoFactory,
                              WorkspaceEventsHandler workspaceEventsHandler,
                              LoaderPresenter loader) {
        this.workspaceServiceClient = workspaceServiceClient;
        this.createWorkspacePresenter = createWorkspacePresenter;
        this.startWorkspacePresenter = startWorkspacePresenter;
        this.locale = locale;
        this.dtoUnmarshallerFactory = dtoUnmarshallerFactory;
        this.eventBus = eventBus;
        this.appContext = appContext;
        this.notificationManager = notificationManager;
        this.messageBusProvider = messageBusProvider;
        this.browserAddress = browserAddress;
        this.dialogFactory = dialogFactory;
        this.preferencesManager = preferencesManager;
        this.dtoFactory = dtoFactory;
        this.workspaceEventsHandler = workspaceEventsHandler;
        this.loader = loader;

        this.needToReloadComponents = true;

        eventBus.addHandler(WsAgentStateEvent.TYPE, this);
        eventBus.addHandler(WorkspaceStoppedEvent.TYPE, this);
    }

    /** {@inheritDoc} */
    @Override
    public void onWorkspaceStopped(WorkspaceStoppedEvent event) {
        setCurrentWorkspace(null);
    }

    /** {@inheritDoc} */
    @Override
    public void onWsAgentStarted(WsAgentStateEvent event) {
        notificationManager.notify(locale.startedWs(), StatusNotification.Status.SUCCESS, FLOAT_MODE);
    }

    /** {@inheritDoc} */
    @Override
    public void onWsAgentStopped(WsAgentStateEvent event) {
    }

    /**
     * Sets workspace to app context as current.
     *
     * @param workspace
     *         workspace which will be current
     */
    public void setCurrentWorkspace(Workspace workspace) {
        appContext.setWorkspace(workspace);

        if (needToReloadComponents) {
            callback.onSuccess(WorkspaceComponent.this);
            needToReloadComponents = false;
        }

        if (workspace != null) {
            browserAddress.setAddress(workspace.getNamespace(), workspace.getConfig().getName());
        }
    }

    /**
     * Listens message bus and handles workspace events.
     *
     * @param workspace
     *         workspace to listen
     * @param callback
     *         callback
     * @param restoreFromSnapshot
     *         restore or not the workspace from snapshot
     */
    public void handleWorkspaceEvents(final WorkspaceDto workspace, final Callback<Component, Exception> callback,
                                      final Boolean restoreFromSnapshot) {
        this.callback = callback;
        if (messageBus != null) {
            messageBus.cancelReconnection();
        }
        messageBus = messageBusProvider.createMessageBus();

        messageBus.addOnOpenHandler(new ConnectionOpenedHandler() {
            @Override
            public void onOpen() {
                loader.show(STARTING_WORKSPACE_RUNTIME);

                messageBus.removeOnOpenHandler(this);

                setCurrentWorkspace(workspace);
                workspaceEventsHandler.trackWorkspaceEvents(workspace, callback);

                final WorkspaceStatus workspaceStatus = workspace.getStatus();
                switch (workspaceStatus) {
                    case SNAPSHOTTING:
                        loader.show(CREATING_WORKSPACE_SNAPSHOT);
                        break;
                    case STARTING:
                        eventBus.fireEvent(new WorkspaceStartingEvent(workspace));
                        break;
                    case RUNNING:
                        Scheduler.get().scheduleDeferred(new Scheduler.ScheduledCommand() {
                            @Override
                            public void execute() {
                                loader.setSuccess(STARTING_WORKSPACE_RUNTIME);
                                eventBus.fireEvent(new WorkspaceStartedEvent(workspace));
                            }
                        });
                        break;
                    default:
                        workspaceServiceClient.getSettings() //
                                              .then(new Function<Map<String, String>, Map<String, String>>() {
                                                  @Override
                                                  public Map<String, String> apply(Map<String, String> settings) throws FunctionException {
                                                      if (Boolean.parseBoolean(settings.getOrDefault(CHE_WORKSPACE_AUTO_START, "true"))) {
                                                          startWorkspaceById(workspace.getId(), workspace.getConfig().getDefaultEnv(), restoreFromSnapshot);
                                                      } else {
                                                          loader.show(WORKSPACE_STOPPED);
                                                      }
                                                      return settings;
                                                  }
                                              });
                }
            }
        });
    }

    /**
     * Starts workspace.
     *
     * @param workspaceID
     *         workspace ID to start
     * @param callback
     *         callback
     * @param restoreFromSnapshot
     *         restore or not the workspace from snapshot
     */
    public void startWorkspace(final String workspaceID, final Callback<Component, Exception> callback,
                               final Boolean restoreFromSnapshot) {
        workspaceServiceClient.getWorkspace(workspaceID).then(new Operation<WorkspaceDto>() {
            @Override
            public void apply(WorkspaceDto workspace) throws OperationException {
                handleWorkspaceEvents(workspace, callback, restoreFromSnapshot);
            }
        });
    }

    /**
     * Starts workspace by id when web socket connected.
     *
     * @param workspace
     *         workspace which will be started
     * @param callback
     *         callback to be executed
     */
    public void startWorkspace(final Workspace workspace, final Callback<Component, Exception> callback) {
        startWorkspace(workspace.getId(), callback, null);
    }

    /**
     * Sends a message to the parent frame to inform that IDE application can be shown.
     */
    private native void notifyShowIDE() /*-{
        $wnd.parent.postMessage("show-ide", "*");
    }-*/;

    /**
     * Starts specified workspace if it's {@link WorkspaceStatus} different of {@code RUNNING}
     */
    protected Operation<WorkspaceDto> startWorkspace() {
        return new Operation<WorkspaceDto>() {
            @Override
            public void apply(WorkspaceDto workspaceToStart) throws OperationException {
                startWorkspace(workspaceToStart, callback);
            }
        };
    }

    abstract void tryStartWorkspace();

    private void startWorkspaceById(String workspaceId, String defaultEnvironment, Boolean restoreFromSnapshot) {
        loader.show(STARTING_WORKSPACE_RUNTIME);
        workspaceServiceClient.startById(workspaceId, defaultEnvironment, restoreFromSnapshot).catchError(new Operation<PromiseError>() {
            @Override
            public void apply(PromiseError error) throws OperationException {
                notificationManager.notify(locale.startWsErrorTitle(), error.getMessage(), StatusNotification.Status.FAIL, FLOAT_MODE);
                loader.setError(STARTING_WORKSPACE_RUNTIME);
            }
        });
    }

}
