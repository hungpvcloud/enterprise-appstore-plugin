package com.enterprise.appstore;

import androidx.core.content.FileProvider;

/**
 * Custom FileProvider để tránh conflict với các plugin khác
 * (cordova-plugin-camera, v.v.)
 */
public class EnterpriseAppStoreFileProvider extends FileProvider {
    // Empty class — chỉ cần extend FileProvider
    // với authority riêng của plugin này
}