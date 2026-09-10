# Activity Result API（选择结果回调）

> 来源：Android Developers 官方文档（Google）
> 原文链接：https://developer.android.com/training/basics/intents/result?hl=zh-cn
> 页面标题：获取 activity 的结果

    
    
      
    

    
      
      使用集合让一切井井有条
    
    
      
      根据您的偏好保存内容并对其进行分类。
> 快照时间：2026-09-10

---
启动另一个 activity（无论是您应用中的 activity 还是其他应用中的 activity）不一定要是单向操作。您也可以启动一个 activity 并接收返回的结果。例如，您的应用可启动相机应用并接收拍摄的照片作为结果。或者，您可以启动“通讯录”应用以便用户选择联系人，然后接收联系人详细信息作为结果。

虽然所有 API 级别的 `Activity` 类均提供底层 [`startActivityForResult()`](https://developer.android.com/reference/android/app/Activity?hl=zh-cn#startActivityForResult\(android.content.Intent,%20int\)) 和 [`onActivityResult()`](https://developer.android.com/reference/android/app/Activity?hl=zh-cn#onActivityResult\(int,%20int,%20android.content.Intent\)) API，但 Google 强烈建议您使用 AndroidX [`Activity`](https://developer.android.com/jetpack/androidx/releases/activity?hl=zh-cn) 和 [`Fragment`](https://developer.android.com/jetpack/androidx/releases/fragment?hl=zh-cn) 类中引入的 Activity Result API。

Activity Result API 提供了用于注册结果的组件， 启动生成结果的 activity，并在完成后处理结果 由系统进行分派

## 针对 activity 结果注册回调

在启动 activity 以获取结果时，可能会出现您的进程和 activity 因内存不足而被销毁的情况；如果是使用相机等内存密集型操作，几乎可以确定会出现这种情况。

因此，Activity Result API 会将结果回调从您之前启动另一个 activity 的代码位置分离开来。由于在重新创建进程和 activity 时需要使用结果回调，因此每次创建 activity 时都必须无条件注册回调，即使启动另一个 activity 的逻辑仅基于用户输入内容或其他业务逻辑也是如此。

位于 [`ComponentActivity`](https://developer.android.com/reference/androidx/activity/ComponentActivity?hl=zh-cn) 或 [`Fragment`](https://developer.android.com/reference/androidx/fragment/app/Fragment?hl=zh-cn) 中时，Activity Result API 会提供 [`registerForActivityResult()`](https://developer.android.com/reference/androidx/activity/result/ActivityResultCaller?hl=zh-cn#public-methods_1) API，用于注册结果回调。`registerForActivityResult()` 接受 [`ActivityResultContract`](https://developer.android.com/reference/androidx/activity/result/contract/ActivityResultContract?hl=zh-cn) 和 [`ActivityResultCallback`](https://developer.android.com/reference/androidx/activity/result/ActivityResultCallback?hl=zh-cn) 作为参数，并返回 [`ActivityResultLauncher`](https://developer.android.com/reference/androidx/activity/result/ActivityResultLauncher?hl=zh-cn)，供您用来启动另一个 activity。

`ActivityResultContract` 定义生成结果所需的输入类型以及结果的输出类型。这些 API 可为拍照和请求权限等基本 intent 操作提供[默认协定](https://developer.android.com/reference/androidx/activity/result/contract/ActivityResultContracts?hl=zh-cn)。您还可以[创建自定义协定](#custom)。

`ActivityResultCallback` 是单一方法接口，带有 [`onActivityResult()`](https://developer.android.com/reference/androidx/activity/result/ActivityResultCallback?hl=zh-cn#onActivityResult\(kotlin.Any\)) 方法，可接受 `ActivityResultContract` 中定义的输出类型的对象：

### Kotlin

val getContent \= registerForActivityResult(GetContent()) { uri: Uri? \->
    // Handle the returned Uri
}

### Java

// GetContent creates an ActivityResultLauncher<String> to let you pass
// in the mime type you want to let the user select
ActivityResultLauncher<String\> mGetContent \= registerForActivityResult(new GetContent(),
    new ActivityResultCallback<Uri\>() {
        @Override
        public void onActivityResult(Uri uri) {
            // Handle the returned Uri
        }
});

如果您有多个使用不同协定或需要单独回调的 activity 结果调用，则可以多次调用 `registerForActivityResult()`，以注册多个 `ActivityResultLauncher` 实例。每次创建 fragment 或 activity 时，都必须按照相同的顺序调用 `registerForActivityResult()`，以便将生成的结果传递给正确的回调。

在 fragment 或 activity 创建完毕之前可安全地调用 `registerForActivityResult()`，因此，在为返回的 `ActivityResultLauncher` 实例声明成员变量时可以直接使用它。

**注意**：您必须在创建 fragment 或 activity 之前调用 `registerForActivityResult()`；但在 fragment 或 activity 的 [`Lifecycle`](https://developer.android.com/reference/androidx/lifecycle/Lifecycle?hl=zh-cn) 达到 [`CREATED`](https://developer.android.com/reference/androidx/lifecycle/Lifecycle.State?hl=zh-cn#CREATED) 之前，您将无法启动 `ActivityResultLauncher`。

## 启动 activity 以获取结果

虽然 `registerForActivityResult()` 会注册您的回调，但它不会启动另一个 activity 并发出结果请求。这些操作由返回的 `ActivityResultLauncher` 实例负责。

如果存在输入内容，启动器会接受与 `ActivityResultContract` 的类型匹配的输入内容。调用 [`launch()`](https://developer.android.com/reference/androidx/activity/result/ActivityResultLauncher?hl=zh-cn#launch\(I\)) 会启动生成结果的过程。当用户完成后续 activity 并返回时，系统将执行 `ActivityResultCallback` 中的 `onActivityResult()`，如以下示例所示：

### Kotlin

val getContent \= registerForActivityResult(GetContent()) { uri: Uri? \->
    // Handle the returned Uri
}

override fun onCreate(savedInstanceState: Bundle?) {
    // ...

    val selectButton \= findViewById<Button\>(R.id.select\_button)

    selectButton.setOnClickListener {
        // Pass in the mime type you want to let the user select
        // as the input
        getContent.launch("image/\*")
    }
}

### Java

ActivityResultLauncher<String\> mGetContent \= registerForActivityResult(new GetContent(),
    new ActivityResultCallback<Uri\>() {
        @Override
        public void onActivityResult(Uri uri) {
            // Handle the returned Uri
        }
});

@Override
public void onCreate(@Nullable Bundle savedInstanceState) {
    // ...

    Button selectButton \= findViewById(R.id.select\_button);

    selectButton.setOnClickListener(new OnClickListener() {
        @Override
        public void onClick(View view) {
            // Pass in the mime type you want to let the user select
            // as the input
            mGetContent.launch("image/\*");
        }
    });
}

除了传递输入内容之外，[`launch()`](https://developer.android.com/reference/androidx/activity/result/ActivityResultLauncher?hl=zh-cn#launch\(I,%20androidx.core.app.ActivityOptionsCompat\)) 的重载版本还允许您传递 [`ActivityOptionsCompat`](https://developer.android.com/reference/androidx/core/app/ActivityOptionsCompat?hl=zh-cn)。

**注意**：由于在调用 `launch()` 与触发 `onActivityResult()` 回调的两个时间点之间，您的进程和 activity 可能会被销毁，因此，处理结果所需的任何其他状态都必须与这些 API 分开保存和恢复。

## 在单独的类中接收 activity 结果

虽然 `ComponentActivity` 和 `Fragment` 类通过实现 [`ActivityResultCaller`](https://developer.android.com/reference/androidx/activity/result/ActivityResultCaller?hl=zh-cn) 接口来允许您使用 `registerForActivityResult()` API，但您也可以直接使用 [`ActivityResultRegistry`](https://developer.android.com/reference/androidx/activity/result/ActivityResultRegistry?hl=zh-cn) 在未实现 `ActivityResultCaller` 的单独类中接收 activity 结果。

例如，您可能需要实现一个 [`LifecycleObserver`](https://developer.android.com/reference/kotlin/androidx/lifecycle/LifecycleObserver?hl=zh-cn)，用于处理协定的注册和启动器的启动：

### Kotlin

class MyLifecycleObserver(private val registry : ActivityResultRegistry)
        : DefaultLifecycleObserver {
    lateinit var getContent : ActivityResultLauncher<String\>

    override fun onCreate(owner: LifecycleOwner) {
        getContent \= registry.register("key", owner, GetContent()) { uri \->
            // Handle the returned Uri
        }
    }

    fun selectImage() {
        getContent.launch("image/\*")
    }
}

class MyFragment : Fragment() {
    lateinit var observer : MyLifecycleObserver

    override fun onCreate(savedInstanceState: Bundle?) {
        // ...

        observer \= MyLifecycleObserver(requireActivity().activityResultRegistry)
        lifecycle.addObserver(observer)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val selectButton \= view.findViewById<Button\>(R.id.select\_button)

        selectButton.setOnClickListener {
            // Open the activity to select an image
            observer.selectImage()
        }
    }
}

### Java

class MyLifecycleObserver implements DefaultLifecycleObserver {
    private final ActivityResultRegistry mRegistry;
    private ActivityResultLauncher<String\> mGetContent;

    MyLifecycleObserver(@NonNull ActivityResultRegistry registry) {
        mRegistry \= registry;
    }

    public void onCreate(@NonNull LifecycleOwner owner) {
        // ...

        mGetContent \= mRegistry.register(“key”, owner, new GetContent(),
            new ActivityResultCallback<Uri\>() {
                @Override
                public void onActivityResult(Uri uri) {
                    // Handle the returned Uri
                }
            });
    }

    public void selectImage() {
        // Open the activity to select an image
        mGetContent.launch("image/\*");
    }
}

class MyFragment extends Fragment {
    private MyLifecycleObserver mObserver;

    @Override
    void onCreate(Bundle savedInstanceState) {
        // ...

        mObserver \= new MyLifecycleObserver(requireActivity().getActivityResultRegistry());
        getLifecycle().addObserver(mObserver);
    }

    @Override
    void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        Button selectButton \= findViewById(R.id.select\_button);
        selectButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
                mObserver.selectImage();
            }
        });
    }
}

使用 `ActivityResultRegistry` API 时，Google 强烈建议您使用可接受 `LifecycleOwner` 作为参数的 API，因为 `LifecycleOwner` 会在 `Lifecycle` 被销毁时自动移除已注册的启动器。不过，如果 `LifecycleOwner` 不可用，每个 `ActivityResultLauncher` 类都允许您手动调用 [`unregister()`](https://developer.android.com/reference/androidx/activity/result/ActivityResultLauncher?hl=zh-cn#unregister\(\)) 作为替代。

## 测试

默认情况下，`registerForActivityResult()` 会自动使用 activity 提供的 `ActivityResultRegistry`。此外，它还提供了一个重载，让您可以传入自己的 `ActivityResultRegistry` 实例。该实例可用于测试您的 activity 结果调用，无需实际启动另一个 activity。

[测试应用的 fragment](https://developer.android.com/training/basics/fragments/testing?hl=zh-cn) 时，使用 [`FragmentFactory`](https://developer.android.com/reference/androidx/fragment/app/FragmentFactory?hl=zh-cn) 将 `ActivityResultRegistry` 传入该 fragment 的构造函数即可提供测试 `ActivityResultRegistry`。

**注意**：任何可让您在测试中注入单独 `ActivityResultRegistry` 的机制都足以支持测试您的 activity 结果调用。

例如，使用 `TakePicturePreview` 协定获取图片缩略图的 fragment 可能按类似如下所示的方式编写：

### Kotlin

class MyFragment(
    private val registry: ActivityResultRegistry
) : Fragment() {
    val thumbnailLiveData \= MutableLiveData<Bitmap?>

    val takePicture \= registerForActivityResult(TakePicturePreview(), registry) {
        bitmap: Bitmap? \-> thumbnailLiveData.setValue(bitmap)
    }

    // ...
}

### Java

public class MyFragment extends Fragment {
    private final ActivityResultRegistry mRegistry;
    private final MutableLiveData<Bitmap\> mThumbnailLiveData \= new MutableLiveData();
    private final ActivityResultLauncher<Void\> mTakePicture \=
        registerForActivityResult(new TakePicturePreview(), mRegistry, new ActivityResultCallback<Bitmap\>() {
            @Override
            public void onActivityResult(Bitmap thumbnail) {
                mThumbnailLiveData.setValue(thumbnail);
            }
        });

    public MyFragment(@NonNull ActivityResultRegistry registry) {
        super();
        mRegistry \= registry;
    }

    @VisibleForTesting
    @NonNull
    ActivityResultLauncher<Void\> getTakePicture() {
        return mTakePicture;
    }

    @VisibleForTesting
    @NonNull
    LiveData<Bitmap\> getThumbnailLiveData() {
        return mThumbnailLiveData;
    }

    // ...
}

创建专用于测试的 `ActivityResultRegistry` 时，必须实现 [`onLaunch()`](https://developer.android.com/reference/androidx/activity/result/ActivityResultRegistry?hl=zh-cn#onLaunch\(int,androidx.activity.result.contract.ActivityResultContract%3CI,O%3E,I,androidx.core.app.ActivityOptionsCompat\)) 方法。您的测试实现可以直接调用 [`dispatchResult()`](https://developer.android.com/reference/androidx/activity/result/ActivityResultRegistry?hl=zh-cn#dispatchResult\(int,%20O\))，而不是调用 `startActivityForResult()`，从而提供要在测试中使用的确切结果：

```
val testRegistry = object : ActivityResultRegistry() {
    override fun <I, O> onLaunch(
            requestCode: Int,
            contract: ActivityResultContract<I, O>,
            input: I,
            options: ActivityOptionsCompat?
    ) {
        dispatchResult(requestCode, expectedResult)
    }
}
```

完整的测试会创建预期结果，构造一个测试 `ActivityResultRegistry`，将其传递给 fragment，直接触发启动器或使用 Espresso 等其他测试 API 来触发启动器，然后验证结果：

```
@Test
fun activityResultTest {
    // Create an expected result Bitmap
    val expectedResult = Bitmap.createBitmap(1, 1, Bitmap.Config.RGBA_F16)

    // Create the test ActivityResultRegistry
    val testRegistry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(
            requestCode: Int,
            contract: ActivityResultContract<I, O>,
            input: I,
            options: ActivityOptionsCompat?
        ) {
            dispatchResult(requestCode, expectedResult)
        }
    }

    // Use the launchFragmentInContainer method that takes a
    // lambda to construct the Fragment with the testRegistry
    with(launchFragmentInContainer { MyFragment(testRegistry) }) {
            onFragment { fragment ->
                // Trigger the ActivityResultLauncher
                fragment.takePicture()
                // Verify the result is set
                assertThat(fragment.thumbnailLiveData.value)
                        .isSameInstanceAs(expectedResult)
            }
    }
}
```

## 创建自定义协定

虽然 [`ActivityResultContracts`](https://developer.android.com/reference/androidx/activity/result/contract/ActivityResultContracts?hl=zh-cn) 包含一些预先构建的 `ActivityResultContract` 类供您使用，但您可以使用自己的协定，提供您所需要的精确类型安全 API。

每个 `ActivityResultContract` 都需要定义好的输入和输出类，如果您不需要任何输入，请使用 `Void` 作为输入类型（在 Kotlin 中，请使用 `Void?` 或 `Unit`）。

每个协定都必须实现 [`createIntent()`](https://developer.android.com/reference/androidx/activity/result/contract/ActivityResultContract?hl=zh-cn#createIntent\(android.content.Context,kotlin.Any\)) 方法，该方法接受 `Context` 和输入内容作为参数，并构造将与 `startActivityForResult()` 配合使用的 `Intent`。

每个协定还必须实现 [`parseResult()`](https://developer.android.com/reference/androidx/activity/result/contract/ActivityResultContract?hl=zh-cn#parseResult\(kotlin.Int,android.content.Intent\))，它会根据指定的 `resultCode`（如 `Activity.RESULT_OK` 或 `Activity.RESULT_CANCELED`）和 `Intent` 生成输出。

如果无需调用 `createIntent()`、启动另一个 activity 并借助 `parseResult()` 来构建结果即可确定指定输入内容的结果，协定可以选择性地实现 [`getSynchronousResult()`](https://developer.android.com/reference/androidx/activity/result/contract/ActivityResultContract?hl=zh-cn#getSynchronousResult\(android.content.Context,kotlin.Any\))。

以下示例展示了如何构造 `ActivityResultContract`：

### Kotlin

class PickRingtone : ActivityResultContract<Int, Uri?>() {
    override fun createIntent(context: Context, ringtoneType: Int) \=
        Intent(RingtoneManager.ACTION\_RINGTONE\_PICKER).apply {
            putExtra(RingtoneManager.EXTRA\_RINGTONE\_TYPE, ringtoneType)
        }

    override fun parseResult(resultCode: Int, result: Intent?) : Uri? {
        if (resultCode != Activity.RESULT\_OK) {
            return null
        }
        return result?.getParcelableExtra(RingtoneManager.EXTRA\_RINGTONE\_PICKED\_URI)
    }
}

### Java

public class PickRingtone extends ActivityResultContract<Integer, Uri\> {
    @NonNull
    @Override
    public Intent createIntent(@NonNull Context context, @NonNull Integer ringtoneType) {
        Intent intent \= new Intent(Intent.ACTION\_GET\_CONTENT);
        intent.putExtra(RingtoneManager.EXTRA\_RINGTONE\_TYPE, ringtoneType.intValue());
        return intent;
    }

    @Override
    public Uri parseResult(int resultCode, @Nullable Intent result) {
        if (resultCode != Activity.RESULT\_OK || result \== null) {
            return null;
        }
        return result.getParcelableExtra(RingtoneManager.EXTRA\_RINGTONE\_PICKED\_URI);
    }
}

如果您不需要自定义协定，则可以使用 [`StartActivityForResult`](https://developer.android.com/reference/androidx/activity/result/contract/ActivityResultContracts.StartActivityForResult?hl=zh-cn) 协定。这是一个通用协定，它可接受任何 `Intent` 作为输入内容并返回 [`ActivityResult`](https://developer.android.com/reference/androidx/activity/result/ActivityResult?hl=zh-cn)，让您能够在回调中提取 `resultCode` 和 `Intent`，如以下示例所示：

### Kotlin

val startForResult \= registerForActivityResult(StartActivityForResult()) { result: ActivityResult \->
    if (result.resultCode \== Activity.RESULT\_OK) {
        val intent \= result.data
        // Handle the Intent
    }
}

override fun onCreate(savedInstanceState: Bundle) {
    // ...

    val startButton \= findViewById(R.id.start\_button)

    startButton.setOnClickListener {
        // Use the Kotlin extension in activity-ktx
        // passing it the Intent you want to start
        startForResult.launch(Intent(this, ResultProducingActivity::class.java))
    }
}

### Java

ActivityResultLauncher<Intent\> mStartForResult \= registerForActivityResult(new StartActivityForResult(),
        new ActivityResultCallback<ActivityResult\>() {
    @Override
    public void onActivityResult(ActivityResult result) {
        if (result.getResultCode() \== Activity.RESULT\_OK) {
            Intent intent \= result.getData();
            // Handle the Intent
        }
    }
});

@Override
public void onCreate(@Nullable savedInstanceState: Bundle) {
    // ...

    Button startButton \= findViewById(R.id.start\_button);

    startButton.setOnClickListener(new OnClickListener() {
        @Override
        public void onClick(View view) {
            // The launcher with the Intent you want to start
            mStartForResult.launch(new Intent(this, ResultProducingActivity.class));
        }
    });
}
