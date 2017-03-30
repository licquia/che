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
package org.eclipse.che.ide.command.toolbar;

import elemental.dom.Element;
import elemental.html.DivElement;
import elemental.html.SpanElement;

import com.google.gwt.safehtml.shared.SafeHtml;
import com.google.gwt.user.client.Timer;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.assistedinject.Assisted;

import org.eclipse.che.ide.api.action.Action;
import org.eclipse.che.ide.api.action.ActionManager;
import org.eclipse.che.ide.api.keybinding.KeyBindingAgent;
import org.eclipse.che.ide.command.palette.CommandsPalettePresenter;
import org.eclipse.che.ide.command.palette.PaletteMessages;
import org.eclipse.che.ide.command.palette.ShowCommandsPaletteAction;
import org.eclipse.che.ide.ui.Tooltip;
import org.eclipse.che.ide.ui.menubutton.MenuButton;
import org.eclipse.che.ide.ui.menubutton.ItemsProvider;
import org.eclipse.che.ide.ui.menubutton.MenuItem;
import org.eclipse.che.ide.util.Pair;
import org.eclipse.che.ide.util.dom.Elements;
import org.eclipse.che.ide.util.input.CharCodeWithModifiers;
import org.eclipse.che.ide.util.input.KeyMapUtil;

import java.util.List;
import java.util.Optional;

import static org.eclipse.che.ide.ui.menu.PositionController.HorizontalAlign.MIDDLE;
import static org.eclipse.che.ide.ui.menu.PositionController.VerticalAlign.BOTTOM;

/** Button for opening Commands Palette. */
class OpenCommandsPaletteButton extends MenuButton {

    private final Provider<ActionManager>   actionManagerProvider;
    private final Provider<KeyBindingAgent> keyBindingAgentProvider;

    @Inject
    OpenCommandsPaletteButton(Provider<CommandsPalettePresenter> commandsPalettePresenterProvider,
                              PaletteMessages messages,
                              Provider<ActionManager> actionManagerProvider,
                              Provider<KeyBindingAgent> keyBindingAgentProvider,
                              Provider<ShowCommandsPaletteAction> showCommandsPaletteActionProvider,
                              @Assisted SafeHtml content) {
        super(content, new ItemsProvider() {
            @Override
            public Optional<MenuItem> getDefaultItem() {
                return Optional.empty();
            }

            @Override
            public List<MenuItem> getItems() {
                return null;
            }

            @Override
            public boolean isGroup(MenuItem item) {
                return false;
            }

            @Override
            public Pair<List<MenuItem>, String> getChildren(MenuItem parent) {
                return null;
            }

            @Override
            public void setDataChangedHandler(DataChangedHandler handler) {
            }
        });

        this.actionManagerProvider = actionManagerProvider;
        this.keyBindingAgentProvider = keyBindingAgentProvider;

        addClickHandler(event -> commandsPalettePresenterProvider.get().showDialog());

        // We need to get action's shortcut but action may not be registered yet.
        // So postpone tooltip creation and wait 1 sec. while action will be registered.
        new Timer() {
            @Override
            public void run() {
                final DivElement divElement = Elements.createDivElement();
                divElement.setInnerText(messages.actionShowPaletteTitle());
                divElement.appendChild(getHotKey(showCommandsPaletteActionProvider.get()));

                Tooltip.create((Element)OpenCommandsPaletteButton.this.getElement(), BOTTOM, MIDDLE, divElement);
            }
        }.schedule(1000);

        ensureDebugId("button-open_command_palette");
    }

    private SpanElement getHotKey(Action action) {
        final SpanElement spanElement = Elements.createSpanElement();
        spanElement.getStyle().setMarginLeft("5px");
        spanElement.getStyle().setColor("#aaaaaa");

        final String actionId = actionManagerProvider.get().getId(action);
        final CharCodeWithModifiers keyBinding = keyBindingAgentProvider.get().getKeyBinding(actionId);
        final String hotKey = KeyMapUtil.getShortcutText(keyBinding);

        if (hotKey != null) {
            spanElement.setInnerText("[" + hotKey + "]");
        }

        return spanElement;
    }
}
