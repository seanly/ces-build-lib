package com.cloudogu.ces.cesbuildlib

import java.util.regex.Matcher
import java.util.regex.Pattern

class ChangelogParser implements Serializable {
    private script
    private Changelog changelog
    Sh sh

    ChangelogParser(script) {
        this.script = script
        this.sh = new Sh(script)
        this.changelog = new Changelog("CHANGELOG.md")
    }

    ChangelogParser(script, changelog) {
        this.script = script
        this.sh = new Sh(script)
        this.changelog = changelog
    }

    String getChangesForVersion(String releaseVersion) {
        def start = getChangelogStartIndex(releaseVersion)
        script.sh "echo 'got start index"
        def end = getChangelogEndIndex(start)
        script.sh "echo 'got end index'"
        def changelog = this.changelog.get()
        script.sh "echo 'got changelog'"
        return formatForJson(changelog.substring(start, end).trim())
    }

    String formatForJson(String string) {
        return string
        .replace("\"", "")
        .replace("'", "")
        .replace("\\", "")
        .replace("\n", "\\n")
    }

    private int getChangelogStartIndex(String releaseVersion) {
        def changelog = this.changelog.get()
        Pattern p = Pattern.compile("## \\[${releaseVersion}\\]")
        Matcher m = p.matcher(changelog)
        if (m.find()) {
            return m.start()
        }
        return -1
    }

    private int getChangelogEndIndex(int start) {
        def changelog = this.changelog.get().substring(start)
        Pattern p = Pattern.compile("([^#]## \\[)|\$");
        Matcher m = p.matcher(changelog);
        if (m.find()) {
            return m.start() + start
        }
        return -1
    }
}
