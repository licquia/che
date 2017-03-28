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
package org.eclipse.che.ide.command.explorer;

import com.google.common.annotations.VisibleForTesting;
import com.google.gwt.core.client.Callback;
import com.google.gwt.user.client.Timer;
import com.google.gwt.user.client.ui.AcceptsOneWidget;
import com.google.gwt.user.client.ui.IsWidget;
import com.google.inject.Inject;
import com.google.inject.Singleton;

import org.eclipse.che.api.promises.client.Operation;
import org.eclipse.che.api.promises.client.OperationException;
import org.eclipse.che.api.promises.client.PromiseError;
import org.eclipse.che.commons.annotation.Nullable;
import org.eclipse.che.ide.DelayedTask;
import org.eclipse.che.ide.api.app.AppContext;
import org.eclipse.che.ide.api.command.CommandGoal;
import org.eclipse.che.ide.api.command.CommandGoalRegistry;
import org.eclipse.che.ide.api.command.CommandImpl;
import org.eclipse.che.ide.api.command.CommandImpl.ApplicableContext;
import org.eclipse.che.ide.api.command.CommandManager;
import org.eclipse.che.ide.api.command.CommandManager.CommandChangedListener;
import org.eclipse.che.ide.api.command.CommandManager.CommandLoadedListener;
import org.eclipse.che.ide.api.command.CommandType;
import org.eclipse.che.ide.api.component.Component;
import org.eclipse.che.ide.api.constraints.Constraints;
import org.eclipse.che.ide.api.dialogs.DialogFactory;
import org.eclipse.che.ide.api.editor.EditorAgent;
import org.eclipse.che.ide.api.notification.NotificationManager;
import org.eclipse.che.ide.api.parts.WorkspaceAgent;
import org.eclipse.che.ide.api.parts.base.BasePresenter;
import org.eclipse.che.ide.command.CommandResources;
import org.eclipse.che.ide.command.CommandUtils;
import org.eclipse.che.ide.command.node.NodeFactory;
import org.eclipse.che.ide.command.type.chooser.CommandTypeChooser;
import org.vectomatic.dom.svg.ui.SVGResource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.eclipse.che.ide.api.notification.StatusNotification.DisplayMode.EMERGE_MODE;
import static org.eclipse.che.ide.api.notification.StatusNotification.Status.FAIL;
import static org.eclipse.che.ide.api.parts.PartStackType.NAVIGATION;

/** Presenter for Commands Explorer. */
@Singleton
public class CommandsExplorerPresenter extends BasePresenter implements CommandsExplorerView.ActionDelegate,
                                                                        Component,
                                                                        CommandChangedListener,
                                                                        CommandLoadedListener {

    private final CommandsExplorerView view;
    private final CommandResources     resources;
    private final WorkspaceAgent       workspaceAgent;
    private final CommandManager       commandManager;
    private final NotificationManager  notificationManager;
    private final CommandTypeChooser   commandTypeChooser;
    private final ExplorerMessages     messages;
    private final RefreshViewTask      refreshViewTask;
    private final DialogFactory        dialogFactory;
    private final NodeFactory          nodeFactory;
    private final EditorAgent          editorAgent;
    private final AppContext           appContext;

    @Inject
    public CommandsExplorerPresenter(CommandsExplorerView view,
                                     CommandResources commandResources,
                                     WorkspaceAgent workspaceAgent,
                                     CommandManager commandManager,
                                     NotificationManager notificationManager,
                                     CommandTypeChooser commandTypeChooser,
                                     ExplorerMessages messages,
                                     RefreshViewTask refreshViewTask,
                                     DialogFactory dialogFactory,
                                     NodeFactory nodeFactory,
                                     EditorAgent editorAgent,
                                     AppContext appContext) {
        this.view = view;
        this.resources = commandResources;
        this.workspaceAgent = workspaceAgent;
        this.commandManager = commandManager;
        this.notificationManager = notificationManager;
        this.commandTypeChooser = commandTypeChooser;
        this.messages = messages;
        this.refreshViewTask = refreshViewTask;
        this.dialogFactory = dialogFactory;
        this.nodeFactory = nodeFactory;
        this.editorAgent = editorAgent;
        this.appContext = appContext;

        view.setDelegate(this);
    }

    @Override
    public void start(Callback<Component, Exception> callback) {
        workspaceAgent.openPart(this, NAVIGATION, Constraints.LAST);

        commandManager.addCommandLoadedListener(this);
        commandManager.addCommandChangedListener(this);

        callback.onSuccess(this);
    }

    @Override
    public void go(AcceptsOneWidget container) {
        refreshView();

        container.setWidget(getView());
    }

    @Override
    public String getTitle() {
        return messages.partTitle();
    }

    @Override
    public IsWidget getView() {
        return view;
    }

    @Nullable
    @Override
    public String getTitleToolTip() {
        return messages.partTooltip();
    }

    @Nullable
    @Override
    public SVGResource getTitleImage() {
        return resources.explorerPart();
    }

    @Override
    public void onCommandAdd(int left, int top) {
        commandTypeChooser.show(left, top).then(createCommand(getDefaultContext()));
    }

    /** Returns the default {@link ApplicableContext} for the new command. */
    private ApplicableContext getDefaultContext() {
        final ApplicableContext context = new ApplicableContext();

        if (appContext.getProjects().length > 0) {
            context.setWorkspaceApplicable(false);

            Arrays.stream(appContext.getProjects())
                  .forEach(p -> context.addProject(p.getPath()));
        }

        return context;
    }

    /** Returns an operation which creates a command with the given context. */
    private Operation<CommandType> createCommand(ApplicableContext context) {
        return selectedCommandType -> {
            final CommandGoal selectedGoal = view.getSelectedGoal();

            if (selectedGoal == null) {
                return;
            }

            commandManager.createCommand(selectedGoal.getId(), selectedCommandType.getId(), context)
                          .then(command -> {
                              refreshViewAndSelectCommand(command);
                              editorAgent.openEditor(nodeFactory.newCommandFileNode(command));
                          })
                          .catchError(showErrorNotification(messages.unableCreate()));
        };
    }

    @Override
    public void onCommandDuplicate(CommandImpl command) {
        commandManager.createCommand(command)
                      .then(this::refreshViewAndSelectCommand)
                      .catchError(showErrorNotification(messages.unableDuplicate()));
    }

    @Override
    public void onCommandRemove(CommandImpl command) {
        dialogFactory.createConfirmDialog(messages.removeCommandConfirmationTitle(),
                                          messages.removeCommandConfirmationMessage(command.getName()),
                                          () -> commandManager.removeCommand(command.getName())
                                                              .catchError(showErrorNotification(messages.unableRemove())),
                                          null).show();
    }

    /** Returns an operation which shows an error notification with the given title. */
    private Operation<PromiseError> showErrorNotification(String title) {
        return err -> {
            notificationManager.notify(title, err.getMessage(), FAIL, EMERGE_MODE);
            throw new OperationException(err.getMessage());
        };
    }

    @Override
    public void onCommandsLoaded() {
        refreshView();
    }

    @Override
    public void onCommandAdded(CommandImpl command) {
        refreshView();
    }

    @Override
    public void onCommandUpdated(CommandImpl previousCommand, CommandImpl command) {
        refreshView();
    }

    @Override
    public void onCommandRemoved(CommandImpl command) {
        refreshView();
    }

    /** Refresh view and preserve current selection. */
    private void refreshView() {
        refreshViewAndSelectCommand(null);
    }

    private void refreshViewAndSelectCommand(CommandImpl command) {
        refreshViewTask.delayAndSelectCommand(command);
    }

    /**
     * {@link DelayedTask} for refreshing the view and optionally selecting the specified command.
     * <p>Tree widget in the view works asynchronously using events
     * and it needs some time to be fully rendered.
     * So successive refreshing view must be called with some delay.
     */
    // since GIN can't instantiate inner classes
    // made it nested in order to allow injection
    @VisibleForTesting
    static class RefreshViewTask extends DelayedTask {

        // 300 milliseconds should be enough to fully refreshing the tree
        private static final int DELAY_MILLIS = 300;

        private final CommandsExplorerView view;
        private final CommandGoalRegistry  goalRegistry;
        private final CommandManager       commandManager;
        private final CommandUtils         commandUtils;

        private CommandImpl commandToSelect;

        @Inject
        public RefreshViewTask(CommandsExplorerView view,
                               CommandGoalRegistry goalRegistry,
                               CommandManager commandManager,
                               CommandUtils commandUtils) {
            this.view = view;
            this.goalRegistry = goalRegistry;
            this.commandManager = commandManager;
            this.commandUtils = commandUtils;
        }

        @Override
        public void onExecute() {
            refreshView();

            if (commandToSelect != null) {
                // wait some time while tree in the view will be fully refreshed
                new Timer() {
                    @Override
                    public void run() {
                        view.selectCommand(commandToSelect);
                    }
                }.schedule(DELAY_MILLIS);
            }
        }

        void delayAndSelectCommand(@Nullable CommandImpl command) {
            if (command != null) {
                commandToSelect = command;
            }

            delay(DELAY_MILLIS);
        }

        private void refreshView() {
            final Map<CommandGoal, List<CommandImpl>> commandsByGoals = new HashMap<>();

            // all predefined commandToSelect goals must be shown in the view
            // so populate map by all registered commandToSelect goals
            for (CommandGoal goal : goalRegistry.getAllPredefinedGoals()) {
                commandsByGoals.put(goal, new ArrayList<>());
            }

            commandsByGoals.putAll(commandUtils.groupCommandsByGoal(commandManager.getCommands()));

            view.setCommands(commandsByGoals);
        }
    }
}
