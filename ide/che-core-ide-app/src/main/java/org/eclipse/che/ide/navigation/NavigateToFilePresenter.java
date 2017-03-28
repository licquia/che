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
package org.eclipse.che.ide.navigation;

import com.google.common.base.Optional;
import com.google.gwt.http.client.URL;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.google.web.bindery.event.shared.EventBus;

import org.eclipse.che.api.project.shared.dto.ItemReference;
import org.eclipse.che.api.promises.client.Operation;
import org.eclipse.che.api.promises.client.OperationException;
import org.eclipse.che.ide.api.app.AppContext;
import org.eclipse.che.ide.api.editor.EditorAgent;
import org.eclipse.che.ide.api.machine.events.WsAgentStateEvent;
import org.eclipse.che.ide.api.machine.events.WsAgentStateHandler;
import org.eclipse.che.ide.api.resources.File;
import org.eclipse.che.ide.resource.Path;
import org.eclipse.che.ide.rest.DtoUnmarshallerFactory;
import org.eclipse.che.ide.util.loging.Log;
import org.eclipse.che.ide.websocket.Message;
import org.eclipse.che.ide.websocket.MessageBuilder;
import org.eclipse.che.ide.websocket.MessageBus;
import org.eclipse.che.ide.websocket.MessageBusProvider;
import org.eclipse.che.ide.websocket.WebSocketException;
import org.eclipse.che.ide.websocket.rest.RequestCallback;
import org.eclipse.che.ide.websocket.rest.Unmarshallable;

import java.util.ArrayList;
import java.util.List;

import static com.google.gwt.http.client.RequestBuilder.GET;
import static org.eclipse.che.ide.MimeType.APPLICATION_JSON;
import static org.eclipse.che.ide.rest.HTTPHeader.ACCEPT;

/**
 * Presenter for file navigation (find file by name and open it).
 *
 * @author Ann Shumilova
 * @author Artem Zatsarynnyi
 * @author Vlad Zhukovskyi
 */
@Singleton
public class NavigateToFilePresenter implements NavigateToFileView.ActionDelegate, WsAgentStateHandler {

    private final MessageBusProvider     messageBusProvider;
    private final EditorAgent            editorAgent;
    private final DtoUnmarshallerFactory dtoUnmarshallerFactory;
    private final NavigateToFileView     view;
    private final AppContext             appContext;

    private String     SEARCH_URL;
    private MessageBus wsMessageBus;

    @Inject
    public NavigateToFilePresenter(NavigateToFileView view,
                                   EventBus eventBus,
                                   DtoUnmarshallerFactory dtoUnmarshallerFactory,
                                   MessageBusProvider messageBusProvider,
                                   AppContext appContext,
                                   EditorAgent editorAgent) {
        this.view = view;
        this.appContext = appContext;
        this.dtoUnmarshallerFactory = dtoUnmarshallerFactory;
        this.messageBusProvider = messageBusProvider;
        this.editorAgent = editorAgent;

        this.view.setDelegate(this);

        eventBus.addHandler(WsAgentStateEvent.TYPE, this);
    }

    @Override
    public void onWsAgentStarted(WsAgentStateEvent event) {
        wsMessageBus = messageBusProvider.getMachineMessageBus();
        SEARCH_URL = "/project/search";
    }

    @Override
    public void onWsAgentStopped(WsAgentStateEvent event) {
    }

    /** Show dialog with view for navigation. */
    public void showDialog() {
        view.showPopup();
    }

    @Override
    public void onFileSelected(Path path) {
        view.hidePopup();

        appContext.getWorkspaceRoot().getFile(path).then(new Operation<Optional<File>>() {
            @Override
            public void apply(Optional<File> optFile) throws OperationException {
                if (optFile.isPresent()) {
                    editorAgent.openEditor(optFile.get());
                }
            }
        });
    }

    @Override
    public void onFileNameChanged(String fileName) {
        if (fileName.isEmpty()) {
            view.showItems(new ArrayList<ItemReference>());
            return;
        }

        // add '*' to allow search files by first letters
        final String url = SEARCH_URL + "/?name=" + URL.encodePathSegment(fileName + "*");
        final Message message = new MessageBuilder(GET, url).header(ACCEPT, APPLICATION_JSON).build();
        final Unmarshallable<List<ItemReference>> unmarshaller = dtoUnmarshallerFactory.newWSListUnmarshaller(ItemReference.class);
        try {
            wsMessageBus.send(message, new RequestCallback<List<ItemReference>>(unmarshaller) {
                @Override
                protected void onSuccess(List<ItemReference> result) {
                    view.showItems(result);
                }

                @Override
                protected void onFailure(Throwable exception) {
                    Log.error(getClass(), exception);
                }
            });
        } catch (WebSocketException e) {
            Log.error(getClass(), e);
        }
    }

}
