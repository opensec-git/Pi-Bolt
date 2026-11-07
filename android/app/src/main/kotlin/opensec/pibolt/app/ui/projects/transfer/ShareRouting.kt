package opensec.pibolt.app.ui.projects.transfer

/** One thing shared into Pi-Bolt: its content URI (as a string), MIME type and display name when known. */
data class SharedItem(val uri: String, val mime: String?, val name: String? = null)

/** What arrived with ACTION_SEND / ACTION_SEND_MULTIPLE. */
data class SharedContent(val items: List<SharedItem>, val text: String? = null, val subject: String? = null) {
    val isEmpty: Boolean get() = items.isEmpty() && text.isNullOrBlank()
}

/** What can be done with shared content. */
enum class ShareAction {
    /** Images go to a project's composer as attachments. */
    AttachImages,

    /** Files are copied into a project (the folder picked). */
    ImportFiles,

    /** A zip becomes a new project. */
    NewProjectFromZip,

    /** Shared text goes into a project's composer. */
    InsertText,
}

/** The actions offered, the first being the default, and whether a project must be picked (every action needs one but a new project). */
data class ShareRoute(val actions: List<ShareAction>, val images: Int, val files: Int) {
    val default: ShareAction? get() = actions.firstOrNull()

    fun needsProject(action: ShareAction): Boolean = action != ShareAction.NewProjectFromZip
}

/**
 * Where content shared from another app goes (pure, unit-tested). Images default to the composer (up to
 * [maxImages]; more than that can only be imported); a single zip defaults to a new project; any other files import
 * into a project; text alone goes into the composer. Mixed images and files import, since only images can be attached.
 */
object ShareRouting {
    /** MIME types accepted as composer images (what the image encoder decodes). */
    private val imageTypes = setOf("image/png", "image/jpeg", "image/jpg", "image/webp", "image/gif", "image/bmp", "image/heic", "image/heif")
    private val zipTypes = setOf("application/zip", "application/x-zip-compressed", "application/x-zip")

    fun isImage(item: SharedItem): Boolean {
        val m = item.mime?.lowercase()
        return m in imageTypes || (m == null || m == "image/*" || m == "application/octet-stream") && extension(item.name) in setOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "heic", "heif")
    }

    fun isZip(item: SharedItem): Boolean = item.mime?.lowercase() in zipTypes || extension(item.name) == "zip"

    fun route(content: SharedContent, maxImages: Int): ShareRoute {
        val images = content.items.count(::isImage)
        val files = content.items.size
        val actions = buildList {
            when {
                files == 0 -> if (!content.text.isNullOrBlank()) add(ShareAction.InsertText)
                images == files && images <= maxImages -> {
                    add(ShareAction.AttachImages)
                    add(ShareAction.ImportFiles)
                }
                files == 1 && isZip(content.items[0]) -> {
                    add(ShareAction.NewProjectFromZip)
                    add(ShareAction.ImportFiles)
                }
                else -> add(ShareAction.ImportFiles)
            }
            // Text that came with files (a caption) can still go to the composer.
            if (files > 0 && !content.text.isNullOrBlank() && ShareAction.AttachImages !in this) add(ShareAction.InsertText)
        }
        return ShareRoute(actions, images, files)
    }

    /** The text to put in the composer: the subject (when it adds something) and the text. */
    fun composerText(content: SharedContent): String {
        val text = content.text.orEmpty().trim()
        val subject = content.subject.orEmpty().trim()
        return if (subject.isNotEmpty() && !text.contains(subject)) "$subject\n$text".trim() else text
    }

    private fun extension(name: String?): String = name.orEmpty().substringAfterLast('/').let { n ->
        val dot = n.lastIndexOf('.')
        if (dot <= 0) "" else n.substring(dot + 1).lowercase()
    }
}
