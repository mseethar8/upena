/*
 * Copyright 2013 Jive Software, Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.jivesoftware.os.upena.uba.service;

import com.jivesoftware.os.routing.bird.http.client.OAuthSigner;
import com.jivesoftware.os.uba.shared.PasswordStore;
import java.io.File;

public class UbaServiceInitializer {

    public UbaService initialize(PasswordStore passwordStore,
        UpenaClient upenaClient,
        RepositoryProvider repositoryProvider,
        String hostKey,
        String workingDir,
        UbaCoordinate ubaCoordinate,
        OAuthSigner signer,
        UbaLog ubaLog) throws Exception {

        File root = new File(new File(workingDir), "services/");
        if (!root.exists() && !root.mkdirs()) {
            throw new RuntimeException("Failed trying to mkdirs for " + root);
        }
        UbaTree tree = new UbaTree(root, new String[]{"cluster", "service", "release", "instance"});
        Uba uba = new Uba(passwordStore, upenaClient, repositoryProvider, ubaCoordinate, tree, ubaLog);
        UbaService conductorService = new UbaService(passwordStore, upenaClient, uba, hostKey);
        return conductorService;
    }

}
