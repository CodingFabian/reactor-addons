'use strict';

import gulp   from 'gulp';
import config from '../config';

gulp.task('watch', config.browser ? ['browserSync'] : [], function () {

    // Scripts are automatically watched by Watchify inside Browserify task
    gulp.watch(config.styles.watch, ['sass']);
    gulp.watch(config.images.src, ['imagemin']);
    gulp.watch(config.sourceDir + 'index.html', ['copyIndex']);
    gulp.watch(config.sourceDir + 'index.appcache', ['copyIndex']);

});