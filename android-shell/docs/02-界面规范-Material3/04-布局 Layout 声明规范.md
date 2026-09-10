# 布局 Layout 声明规范

> 来源：Android Developers 官方文档（Google）
> 原文链接：https://developer.android.com/develop/ui/views/layout/declaring-layout?hl=zh-cn
> 页面标题：布局 Layout 声明规范
> 快照时间：2026-09-10

---
# 视图中的布局

尝试使用 Compose 方式

Jetpack Compose 是推荐用于 Android 的界面工具包。了解如何在 Compose 中使用布局。

[了解 Compose 布局基础知识 →](https://developer.android.com/develop/ui/compose/layouts/basics?hl=zh-cn)

 ![](https://developer.android.com/static/images/android-compose-ui-logo.png?hl=zh-cn)

布局定义了应用中的界面结构（例如 [activity](https://developer.android.com/guide/components/activities?hl=zh-cn) 的界面结构）。布局中的所有元素均使用 `[View](https://developer.android.com/reference/android/view/View?hl=zh-cn)` 和 `[ViewGroup](https://developer.android.com/reference/android/view/ViewGroup?hl=zh-cn)` 对象的层次结构进行构建。`View` 通常用于绘制用户可看到并与之交互的内容。`ViewGroup` 是不可见的容器，用于定义 `View` 和其他 `ViewGroup` 对象的布局结构，如图 1 所示。

![](https://developer.android.com/static/images/viewgroup_2x.png?hl=zh-cn)

**图 1.** 视图层次结构的图示，它定义了一个界面布局。

`View` 对象通常称为“微件”，可以是多个子类之一，例如 `[Button](https://developer.android.com/reference/android/widget/Button?hl=zh-cn)` 或 `[TextView](https://developer.android.com/reference/android/widget/TextView?hl=zh-cn)`。`ViewGroup` 对象通常称为_布局_，可以是提供不同布局结构的众多类型之一，例如 `[LinearLayout](https://developer.android.com/reference/android/widget/LinearLayout?hl=zh-cn)` 或 [`ConstraintLayout`](https://developer.android.com/reference/androidx/constraintlayout/widget/ConstraintLayout?hl=zh-cn)。

您可通过两种方式声明布局：

-   **在 XML 中声明界面元素。**Android 提供对应于 `View` 类及其子类的简明 XML 词汇，如用于 widget 和布局的词汇。您也可使用 Android Studio 的 [布局编辑器](https://developer.android.com/studio/write/layout-editor?hl=zh-cn)，并采用拖放界面来构建 XML 布局。
    
-   **在运行时实例化布局元素。**您的应用能以程序化方式创建 `View` 和 `ViewGroup` 对象并操纵其属性。

通过在 XML 中声明界面，您可以将应用外观代码与控制其行为的代码分开。使用 XML 文件还有助于为不同屏幕尺寸和屏幕方向提供不同布局。如需了解详情，请参阅[支持不同的屏幕尺寸](https://developer.android.com/training/multiscreen/screensizes?hl=zh-cn)。

借助 Android 框架，您可以灵活选择使用两种或其中一种方法来构建应用界面。例如，您可以在 XML 中声明应用的默认布局，然后在运行时修改布局。

**提示**：如需在运行时调试您的布局，请使用[布局检查器](https://developer.android.com/studio/debug/layout-inspector?hl=zh-cn)工具。

## 编写 XML

您可以利用 Android 的 XML 词汇，按照在 HTML 中创建包含一系列嵌套元素的网页的相同方式快速设计界面布局及其包含的屏幕元素。

每个布局文件都必须只包含一个根元素，并且该元素必须是 `View` 或 `ViewGroup` 对象。定义根元素后，您可以子元素的形式添加其他布局对象或 widget，从而逐步构建定义布局的 `View` 层次结构。例如，以下 XML 布局使用垂直 `LinearLayout` 来储存 `TextView` 和 `Button`：

<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
              android:layout\_width="match\_parent"
              android:layout\_height="match\_parent"
              android:orientation="vertical" \>
    <TextView android:id="@+id/text"
              android:layout\_width="wrap\_content"
              android:layout\_height="wrap\_content"
              android:text="Hello, I am a TextView" />
    <Button android:id="@+id/button"
            android:layout\_width="wrap\_content"
            android:layout\_height="wrap\_content"
            android:text="Hello, I am a Button" />
</LinearLayout>

在 XML 中声明布局后，请以 `.xml` 扩展名将文件保存在您 Android 项目的 `res/layout/` 目录中，以便该文件能正确编译。

如需详细了解布局 XML 文件的语法，请参阅[布局资源](https://developer.android.com/guide/topics/resources/layout-resource?hl=zh-cn)。

## 加载 XML 资源

当您编译应用时，系统会将每个 XML 布局文件编译成 `View` 资源。在应用的 `[Activity.onCreate()](https://developer.android.com/reference/android/app/Activity?hl=zh-cn#onCreate\(android.os.Bundle\))` 回调实现中加载布局资源。为此，请调用 `[setContentView()](https://developer.android.com/reference/android/app/Activity?hl=zh-cn#setContentView\(int\))`，并以 `R.layout._layout_file_name_` 形式向应用代码传递对布局资源的引用。例如，如果您的 XML 布局保存为 `main_layout.xml`，请按如下方式为 `Activity` 加载布局资源：

### Kotlin

fun onCreate(savedInstanceState: Bundle) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.main\_layout)
}

### Java

public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.main\_layout);
}

当 `Activity` 启动时，Android 框架会在您的 `Activity` 中调用 `onCreate()` 回调方法。如需详细了解 activity 生命周期，请参阅 [activity 简介](https://developer.android.com/guide/components/activities?hl=zh-cn#Lifecycle)。

## 属性

每个 `View` 和 `ViewGroup` 对象均支持自己的各种 XML 属性。某些属性是 `View` 对象特有的。例如，`TextView` 支持 `textSize` 属性。不过，扩展此类的任何 `View` 对象也会继承这些属性。某些属性是所有 `View` 对象的共有属性，因为它们继承自 `View` 根类，例如 `id` 属性。其他属性被视为_布局参数_，即描述 `View` 对象特定布局方向的属性，如该对象的父 `ViewGroup` 对象所定义的属性。

### ID

任何 `View` 对象均可拥有与之关联的整型 ID，用于在结构树中对 `View` 进行唯一标识。编译应用后，系统会以整型形式引用此 ID，但在布局 XML 文件中，系统通常会以字符串的形式在 `id` 属性中指定该 ID。这是一个所有 `View` 对象共有的 XML 属性，由 `View` 类定义。您经常使用该功能。XML 标记内部的 ID 语法如下：

android:id="@+id/my\_button"

字符串开头处的 _at_ 符号 (@) 指示 XML 解析器应解析并展开 ID 字符串的其余部分，并将其标识为 ID 资源。加号 (+) 表示这是一个新的资源名称，必须创建该名称并将其添加到 `R.java` 文件中的资源内。

Android 框架还提供许多其他 ID 资源。引用 Android 资源 ID 时，不需要加号 ，但必须添加 `android` 软件包命名空间，如下所示：

android:id="@android:id/empty"

`android` 软件包命名空间表示您要从 `android.R` 资源类而非本地资源类引用 ID。

如需创建视图并从应用中引用它们，您可以采用以下常见模式：

1.  在布局文件中定义视图，并为其分配唯一 ID，如以下示例所示：
    
    <Button android:id="@+id/my\_button"
            android:layout\_width="wrap\_content"
            android:layout\_height="wrap\_content"
            android:text="@string/my\_button\_text"/>
    
2.  创建视图对象的实例，并从布局中捕获它（通常使用 `[onCreate()](https://developer.android.com/reference/android/app/Activity?hl=zh-cn#onCreate\(android.os.Bundle\))` 方法），如以下示例所示：
    
    ### Kotlin
    
    val myButton: Button \= findViewById(R.id.my\_button)
    
    ### Java
    
    Button myButton \= (Button) findViewById(R.id.my\_button);
    

创建 `[RelativeLayout](https://developer.android.com/reference/android/widget/RelativeLayout?hl=zh-cn)` 时，请务必为视图对象定义 ID。在相对布局中，同级视图可定义其相对于其他通过唯一 ID 引用的同级视图的布局。

ID 无需在整个树中具有唯一性，但必须在您搜索的树部分中具有唯一性。它通常是整个树，因此最好尽可能使其具有唯一性。

### 布局参数

名为 `layout__something_` 的 XML 布局属性可以为 `View` 定义适合其所在 `ViewGroup` 的布局参数。

每个 `ViewGroup` 类都会实现一个扩展 `[ViewGroup.LayoutParams](https://developer.android.com/reference/android/view/ViewGroup.LayoutParams?hl=zh-cn)` 的嵌套类。此子类包含的属性类型会根据需要为视图组的每个子视图定义尺寸和位置。如图 2 所示，父视图组会为每个子视图（包括子视图组）定义布局参数。

![](https://developer.android.com/static/images/layoutparams.png?hl=zh-cn)

**图 2.** 视图层次结构的图示，其中包含与每个视图关联的布局参数。

每个 `LayoutParams` 子类都有自己的值设置语法。每个子元素都必须定义适合其父元素的 `LayoutParams`，但父元素也可为其子元素定义不同的 `LayoutParams`。

所有视图组均包含宽度和高度（使用 `layout_width` 和 `layout_height`），并且每个视图都必须定义它们。许多 `LayoutParams` 还包括可选的外边距和边框。

您可以指定具有确切尺寸的宽度和高度，但您可能不想经常这样做。更常见的情况是，您会使用以下某种常量来设置宽度或高度：

-   `wrap_content`：指示您的视图将其大小调整为内容所需的尺寸。
-   `match_parent`：指示您的视图尽可能采用其父视图组所允许的最大尺寸。

一般而言，我们不建议使用绝对单位（如像素）来指定布局宽度和高度。更好的方法是使用相对测量单位（如与密度无关的像素单位 \[dp\]、`wrap_content` 或 `match_parent`），因为这样有助于确保您的应用在各类尺寸的设备屏幕上正确显示。[布局资源](https://developer.android.com/guide/topics/resources/layout-resource?hl=zh-cn)中定义了可接受的测量单位类型。

## 布局位置

视图具有矩形几何图形。它拥有一个位置（以一对“水平向左”和“垂直向上”的坐标表示）和两个尺寸（以宽度和高度表示）。位置和尺寸的单位是像素。

您可以通过调用 `[getLeft()](https://developer.android.com/reference/android/view/View?hl=zh-cn#getLeft\(\))` 方法和 `[getTop()](https://developer.android.com/reference/android/view/View?hl=zh-cn#getTop\(\))` 方法来检索视图的位置。前者会返回表示视图的矩形的左侧 (_x_) 坐标。后者会返回表示视图的矩形的顶部 (_y_) 坐标。这些方法会返回视图相对于其父项的位置。例如，如果 `getLeft()` 返回 20，则表示视图位于其直接父项左边缘向右 20 个像素处。

此外，还有一些便捷方法可以避免不必要的计算，即 `[getRight()](https://developer.android.com/reference/android/view/View?hl=zh-cn#getRight\(\))` 和 `[getBottom()](https://developer.android.com/reference/android/view/View?hl=zh-cn#getBottom\(\))`。这些方法会返回表示视图的矩形的右边缘和下边缘的坐标。例如，调用 `getRight()` 类似于进行以下计算：`getLeft() + getWidth()`。

## 尺寸、内边距和外边距

视图尺寸通过宽度和高度表示。视图有两对宽度和高度值。

第一对称为“测量宽度”和“测量高度”。这些尺寸定义视图希望在其父项内具有的大小。您可通过调用 `[getMeasuredWidth()](https://developer.android.com/reference/android/view/View?hl=zh-cn#getMeasuredWidth\(\))` 和 `[getMeasuredHeight()](https://developer.android.com/reference/android/view/View?hl=zh-cn#getMeasuredHeight\(\))` 来获得测量尺寸。

第二对称为“宽度”和“高度”，有时称为“绘制宽度”和“绘制高度”。这些尺寸定义绘制时和布局后，视图在屏幕上的实际尺寸。这些值可以（但不必）与测量宽度和测量高度不同。您可以通过调用 `[getWidth()](https://developer.android.com/reference/android/view/View?hl=zh-cn#getWidth\(\))` 和 `[getHeight()](https://developer.android.com/reference/android/view/View?hl=zh-cn#getHeight\(\))` 来获取宽度和高度。

为了测量尺寸，视图需将其内边距考虑在内。内边距以视图左侧、顶部、右侧和底部各部分的像素数表示。您可以使用内边距以特定数量的像素弥补视图内容。例如，若左侧内边距为 2，则会将视图内容从左边缘向右推 2 个像素。您可以使用 `[setPadding(int, int, int, int)](https://developer.android.com/reference/android/view/View?hl=zh-cn#setPadding\(int,%20int,%20int,%20int\))` 方法设置内边距，并通过调用 `[getPaddingLeft()](https://developer.android.com/reference/android/view/View?hl=zh-cn#getPaddingLeft\(\))`、`[getPaddingTop()](https://developer.android.com/reference/android/view/View?hl=zh-cn#getPaddingTop\(\))`、`[getPaddingRight()](https://developer.android.com/reference/android/view/View?hl=zh-cn#getPaddingRight\(\))` 和 `[getPaddingBottom()](https://developer.android.com/reference/android/view/View?hl=zh-cn#getPaddingBottom\(\))` 查询内边距。

尽管视图可以定义内边距，但它不支持外边距。不过，视图组支持外边距。如需了解详情，请参阅 `[ViewGroup](https://developer.android.com/reference/android/view/ViewGroup?hl=zh-cn)` 和 `[ViewGroup.MarginLayoutParams](https://developer.android.com/reference/android/view/ViewGroup.MarginLayoutParams?hl=zh-cn)`。

如需详细了解维度，请参阅[维度](https://developer.android.com/guide/topics/resources/more-resources?hl=zh-cn#Dimension)。

除了以编程方式设置边距和内边距之外，您还可以在 XML 布局中设置它们，如以下示例所示：

  <?xml version="1.0" encoding="utf-8"?>
  <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
                android:layout\_width="match\_parent"
                android:layout\_height="match\_parent"
                android:orientation="vertical" \>
      <TextView android:id="@+id/text"
                android:layout\_width="wrap\_content"
                android:layout\_height="wrap\_content"
                **android:layout\_margin="16dp"
                android:padding="8dp"**
                android:text="Hello, I am a TextView" />
      <Button android:id="@+id/button"
              android:layout\_width="wrap\_content"
              android:layout\_height="wrap\_content"
              **android:layout\_marginTop="16dp"
              android:paddingBottom="4dp"
              android:paddingEnd="8dp"
              android:paddingStart="8dp"
              android:paddingTop="4dp"**
              android:text="Hello, I am a Button" />
  </LinearLayout>
  

上例展示了边距和内边距的应用。`TextView` 在四周应用了统一的边距和内边距，而 `Button` 则展示了如何将它们分别应用于不同的边缘。

**注意**：建议使用 `paddingStart`、`paddingEnd`、`layout_marginStart` 和 `layout_marginEnd`，而不是 `paddingLeft`、`paddingRight`、`layout_marginLeft` 和 `layout_marginRight`，因为前者在从左到右和从右到左的语言脚本中表现更好。

## 常见布局

`ViewGroup` 类的每个子类都会提供一种独特的方式，以显示您在其中嵌套的视图。最灵活的布局类型，也是提供最佳工具来保持布局层次结构浅显的布局类型是 `[ConstraintLayout](https://developer.android.com/develop/ui/views/layout/constraint-layout?hl=zh-cn)`。

以下是 Android 平台中一些较为常见的内置布局类型。

**注意**：尽管您可以通过将一个或多个布局嵌套在另一个布局内来实现界面设计，但应尽量使布局层次结构保持简洁。 嵌套的布局越少，布局的绘制速度便会越快。扁平的视图层次结构优于深层的视图层次结构。

**[创建线性布局](https://developer.android.com/develop/ui/views/layout/linear?hl=zh-cn)** [![](https://developer.android.com/static/images/ui/linearlayout-small.png?hl=zh-cn)](https://developer.android.com/develop/ui/views/layout/linear?hl=zh-cn)

使用单个水平行或垂直行来组织子项，并在窗口长度超出屏幕长度时创建滚动条。

**[在 WebView 中构建 Web 应用](https://developer.android.com/guide/webapps/webview?hl=zh-cn)** [![](https://developer.android.com/static/images/ui/webview-small.png?hl=zh-cn)](https://developer.android.com/guide/webapps/webview?hl=zh-cn)

显示网页。

## 构建动态列表

如果布局的内容是动态内容或未预先确定的内容，您可以使用 `[RecyclerView](https://developer.android.com/develop/ui/views/layout/recyclerview?hl=zh-cn)` 或 `[AdapterView](https://developer.android.com/develop/ui/views/layout/binding?hl=zh-cn)` 的子类。`RecyclerView` 通常是更好的选择，因为与 `AdapterView` 相比，它能更高效地利用内存。

`RecyclerView` 和 `AdapterView` 可实现以下常见布局：

**[列表](https://developer.android.com/develop/ui/views/layout/recyclerview?hl=zh-cn)** [![](https://developer.android.com/static/images/ui/listview-small.png?hl=zh-cn)](https://developer.android.com/develop/ui/views/layout/recyclerview?hl=zh-cn)

显示滚动的单列列表。

**[网格](https://developer.android.com/develop/ui/views/layout/recyclerview?hl=zh-cn)** [![](https://developer.android.com/static/images/ui/gridview-small.png?hl=zh-cn)](https://developer.android.com/develop/ui/views/layout/recyclerview?hl=zh-cn)

显示滚动的行列网格。

`RecyclerView` 提供了更多可能性，并可以选择[创建自定义布局管理器](https://developer.android.com/develop/ui/views/layout/recyclerview?hl=zh-cn#plan-your-layout)。

### 使用数据填充适配器视图

您可以通过将 `AdapterView` 实例与 `[Adapter](https://developer.android.com/reference/android/widget/Adapter?hl=zh-cn)` 绑定来填充 `[AdapterView](https://developer.android.com/reference/android/widget/AdapterView?hl=zh-cn)`（如 `[ListView](https://developer.android.com/reference/android/widget/ListView?hl=zh-cn)` 或 `[GridView](https://developer.android.com/reference/android/widget/GridView?hl=zh-cn)`），此操作会从外部来源检索数据，并创建表示每个数据条目的 `View`。

Android 提供几个 `Adapter` 子类，用于检索不同种类的数据和构建 `AdapterView` 的视图。最常见的两种适配器是：

`[ArrayAdapter](https://developer.android.com/reference/android/widget/ArrayAdapter?hl=zh-cn)`

请在数据源为数组时使用此适配器。默认情况下，`ArrayAdapter` 会通过对每个数组项调用 `[toString()](https://developer.android.com/reference/java/lang/Object?hl=zh-cn#toString\(\))` 并将内容放入 `TextView`，为每个项创建视图。

例如，如果您想在 `ListView` 中显示某个字符串数组，请使用构造函数初始化一个新的 `ArrayAdapter`，为每个字符串和字符串数组指定布局：

### Kotlin

    val adapter \= ArrayAdapter<String>(this, android.R.layout.simple\_list\_item\_1, myStringArray)
    

### Java

    ArrayAdapter<String> adapter \= new ArrayAdapter<String>(this,
            android.R.layout.simple\_list\_item\_1, myStringArray);
    

此构造函数的实参如下：

-   您的应用 `[Context](https://developer.android.com/reference/android/content/Context?hl=zh-cn)`
-   包含数组中每个字符串的 `TextView` 的布局
-   字符串数组

然后，对您的 `ListView` 调用 `[setAdapter()](https://developer.android.com/reference/android/widget/AdapterView?hl=zh-cn#setAdapter\(T\))`：

### Kotlin

    val listView: ListView \= findViewById(R.id.listview)
    listView.adapter \= adapter
    

### Java

    ListView listView \= (ListView) findViewById(R.id.listview);
    listView.setAdapter(adapter);
    

如需自定义每个项的外观，您可以重写数组中各个对象的 `toString()` 方法。或者，如需为 `TextView` 之外的每个项创建视图（例如，如果您想为每个数组项创建 `[ImageView](https://developer.android.com/reference/android/widget/ImageView?hl=zh-cn)`），请扩展 `ArrayAdapter` 类并替换 `[getView()](https://developer.android.com/reference/android/widget/ArrayAdapter?hl=zh-cn#getView\(int,%20android.view.View,%20android.view.ViewGroup\))`，以返回您想要为每个项获取的视图类型。

`[SimpleCursorAdapter](https://developer.android.com/reference/android/widget/SimpleCursorAdapter?hl=zh-cn)`

请在数据来自 `[Cursor](https://developer.android.com/reference/android/database/Cursor?hl=zh-cn)` 时使用此适配器。 使用 `SimpleCursorAdapter` 时，请指定要为 `Cursor` 中的每个行使用的布局，以及您希望将 `Cursor` 中的哪些列插入到所需布局的视图中。 例如，如果您想创建人员姓名和电话号码列表，则可以执行返回 `Cursor`（包含对应每个人的行，以及对应姓名和号码的列）的查询。然后，您可以创建一个字符串数组，指定您想要在每个结果的布局中包含 `Cursor` 中的哪些列，并创建一个整型数组，指定应放入每个列的对应视图：

### Kotlin

    val fromColumns \= arrayOf(ContactsContract.Data.DISPLAY\_NAME,
                              ContactsContract.CommonDataKinds.Phone.NUMBER)
    val toViews \= intArrayOf(R.id.display\_name, R.id.phone\_number)
    

### Java

    String\[\] fromColumns \= {ContactsContract.Data.DISPLAY\_NAME,
                            ContactsContract.CommonDataKinds.Phone.NUMBER};
    int\[\] toViews \= {R.id.display\_name, R.id.phone\_number};
    

当您实例化 `SimpleCursorAdapter` 时，请传递要用于每个结果的布局、包含结果的 `Cursor` 以及以下两个数组：

### Kotlin

    val adapter \= SimpleCursorAdapter(this,
            R.layout.person\_name\_and\_number, cursor, fromColumns, toViews, 0)
    val listView \= getListView()
    listView.adapter \= adapter
    

### Java

    SimpleCursorAdapter adapter \= new SimpleCursorAdapter(this,
            R.layout.person\_name\_and\_number, cursor, fromColumns, toViews, 0);
    ListView listView \= getListView();
    listView.setAdapter(adapter);
    

然后，`SimpleCursorAdapter` 会使用提供的布局，将每个 `fromColumns` 项插入对应的 `toViews` 视图，从而为 `Cursor` 中的每个行创建视图。

如果您在应用的生命周期内更改了适配器读取的底层数据，请调用 `[notifyDataSetChanged()](https://developer.android.com/reference/android/widget/ArrayAdapter?hl=zh-cn#notifyDataSetChanged\(\))`。这会通知附加的视图数据已被更改，并自行刷新。

### 处理点击事件

您可以实现 `[AdapterView.OnItemClickListener](https://developer.android.com/reference/android/widget/AdapterView.OnItemClickListener?hl=zh-cn)` 接口，从而响应 `AdapterView` 中每一项上的点击事件。例如：

### Kotlin

listView.onItemClickListener \= AdapterView.OnItemClickListener { parent, view, position, id \-\>
    // Do something in response to the click.
}

### Java

// Create a message handling object as an anonymous class.
private OnItemClickListener messageClickedHandler \= new OnItemClickListener() {
    public void onItemClick(AdapterView parent, View v, int position, long id) {
        // Do something in response to the click.
    }
};

listView.setOnItemClickListener(messageClickedHandler);

## 其他资源

如需了解布局在 GitHub 上的 [Sunflower 演示版应用](https://github.com/googlesamples/android-sunflower)中的使用方式，请参阅该应用。
